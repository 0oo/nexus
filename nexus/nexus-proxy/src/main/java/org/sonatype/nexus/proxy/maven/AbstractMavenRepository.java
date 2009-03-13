/**
 * Sonatype Nexus (TM) Open Source Version.
 * Copyright (c) 2008 Sonatype, Inc. All rights reserved.
 * Includes the third-party code listed at http://nexus.sonatype.org/dev/attributions.html
 * This program is licensed to you under Version 3 only of the GNU General Public License as published by the Free Software Foundation.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License Version 3 for more details.
 * You should have received a copy of the GNU General Public License Version 3 along with this program.
 * If not, see http://www.gnu.org/licenses/.
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc.
 * "Sonatype" and "Sonatype Nexus" are trademarks of Sonatype, Inc.
 */
package org.sonatype.nexus.proxy.maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.nexus.artifact.NexusItemInfo;
import org.sonatype.nexus.feeds.NexusArtifactEvent;
import org.sonatype.nexus.proxy.AccessDeniedException;
import org.sonatype.nexus.proxy.IllegalOperationException;
import org.sonatype.nexus.proxy.ItemNotFoundException;
import org.sonatype.nexus.proxy.RemoteAccessException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.StorageException;
import org.sonatype.nexus.proxy.attributes.inspectors.DigestCalculatingInspector;
import org.sonatype.nexus.proxy.events.RepositoryEventEvictUnusedItems;
import org.sonatype.nexus.proxy.events.RepositoryEventRecreateMavenMetadata;
import org.sonatype.nexus.proxy.item.AbstractStorageItem;
import org.sonatype.nexus.proxy.item.DefaultStorageFileItem;
import org.sonatype.nexus.proxy.item.RepositoryItemUid;
import org.sonatype.nexus.proxy.item.StorageCollectionItem;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.maven.EvictUnusedMavenItemsWalkerProcessor.EvictUnusedMavenItemsWalkerFilter;
import org.sonatype.nexus.proxy.repository.AbstractProxyRepository;
import org.sonatype.nexus.proxy.repository.DefaultRepositoryKind;
import org.sonatype.nexus.proxy.repository.HostedRepository;
import org.sonatype.nexus.proxy.repository.MutableProxyRepositoryKind;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.nexus.proxy.repository.RepositoryKind;
import org.sonatype.nexus.proxy.repository.RepositoryRequest;
import org.sonatype.nexus.proxy.storage.UnsupportedStorageOperationException;
import org.sonatype.nexus.proxy.walker.DefaultWalkerContext;
import org.sonatype.nexus.util.ItemPathUtils;

/**
 * The abstract (layout unaware) Maven Repository.
 * 
 * @author cstamas
 */
public abstract class AbstractMavenRepository
    extends AbstractProxyRepository
    implements MavenRepository, MavenHostedRepository, MavenProxyRepository
{
    /**
     * Metadata manager.
     */
    @Requirement
    private MetadataManager metadataManager;

    /**
     * The artifact packaging mapper.
     */
    @Requirement
    private ArtifactPackagingMapper artifactPackagingMapper;

    private MutableProxyRepositoryKind repositoryKind;

    private ArtifactStoreHelper artifactStoreHelper;

    /** Maven repository policy */
    private RepositoryPolicy repositoryPolicy = RepositoryPolicy.RELEASE;

    /** Should repository metadata be cleaned? */
    private boolean cleanseRepositoryMetadata = false;

    /** Should repository provide correct checksums even if wrong ones are in repo? */
    private boolean fixRepositoryChecksums = false;

    private boolean downloadRemoteIndexes;

    /**
     * The release max age (in minutes).
     */
    private int releaseMaxAge = 24 * 60;

    /**
     * The snapshot max age (in minutes).
     */
    private int snapshotMaxAge = 24 * 60;

    /**
     * The metadata max age (in minutes).
     */
    private int metadataMaxAge = 24 * 60;

    /**
     * Checksum policy applied in this Maven repository.
     */
    private ChecksumPolicy checksumPolicy;

    public ArtifactStoreHelper getArtifactStoreHelper()
    {
        if ( artifactStoreHelper == null )
        {
            artifactStoreHelper = new ArtifactStoreHelper( this );
        }

        return artifactStoreHelper;
    }

    public ArtifactPackagingMapper getArtifactPackagingMapper()
    {
        return artifactPackagingMapper;
    }

    /**
     * Override the "default" kind with Maven specifics.
     */
    public RepositoryKind getRepositoryKind()
    {
        if ( repositoryKind == null )
        {
            repositoryKind = new MutableProxyRepositoryKind( this, Arrays
                .asList( new Class<?>[] { MavenRepository.class } ), new DefaultRepositoryKind(
                MavenHostedRepository.class,
                null ), new DefaultRepositoryKind( MavenProxyRepository.class, null ) );
        }

        return repositoryKind;
    }

    @Override
    public Collection<String> evictUnusedItems( ResourceStoreRequest request, final long timestamp )
    {
        EvictUnusedMavenItemsWalkerProcessor walkerProcessor = new EvictUnusedMavenItemsWalkerProcessor( timestamp );

        DefaultWalkerContext ctx = new DefaultWalkerContext( this, request, new EvictUnusedMavenItemsWalkerFilter() );

        ctx.getProcessors().add( walkerProcessor );

        getWalker().walk( ctx );

        getApplicationEventMulticaster().notifyProximityEventListeners( new RepositoryEventEvictUnusedItems( this ) );

        return walkerProcessor.getFiles();
    }

    public boolean recreateMavenMetadata( ResourceStoreRequest request )
    {
        if ( !getRepositoryKind().isFacetAvailable( HostedRepository.class ) )
        {
            return false;
        }

        if ( StringUtils.isEmpty( request.getRequestPath() ) )
        {
            request.setRequestPath( RepositoryItemUid.PATH_ROOT );
        }

        getLogger().info(
            "Recreating Maven2 metadata in repository ID='" + getId() + "' from path='" + request.getRequestPath()
                + "'" );

        RecreateMavenMetadataWalkerProcessor wp = new RecreateMavenMetadataWalkerProcessor();

        DefaultWalkerContext ctx = new DefaultWalkerContext( this, request );

        ctx.getProcessors().add( wp );

        getWalker().walk( ctx );

        getApplicationEventMulticaster()
            .notifyProximityEventListeners( new RepositoryEventRecreateMavenMetadata( this ) );

        return !ctx.isStopped();
    }

    public boolean isDownloadRemoteIndexes()
    {
        return downloadRemoteIndexes;
    }

    public void setDownloadRemoteIndexes( boolean downloadRemoteIndexes )
    {
        this.downloadRemoteIndexes = downloadRemoteIndexes;
    }

    public RepositoryPolicy getRepositoryPolicy()
    {
        return repositoryPolicy;
    }

    public void setRepositoryPolicy( RepositoryPolicy repositoryPolicy )
    {
        this.repositoryPolicy = repositoryPolicy;
    }

    public boolean isCleanseRepositoryMetadata()
    {
        return cleanseRepositoryMetadata;
    }

    public void setCleanseRepositoryMetadata( boolean cleanseRepositoryMetadata )
    {
        this.cleanseRepositoryMetadata = cleanseRepositoryMetadata;
    }

    public boolean isFixRepositoryChecksums()
    {
        return fixRepositoryChecksums;
    }

    public void setFixRepositoryChecksums( boolean fixRepositoryChecksums )
    {
        this.fixRepositoryChecksums = fixRepositoryChecksums;
    }

    public ChecksumPolicy getChecksumPolicy()
    {
        return checksumPolicy;
    }

    public void setChecksumPolicy( ChecksumPolicy checksumPolicy )
    {
        this.checksumPolicy = checksumPolicy;
    }

    public int getReleaseMaxAge()
    {
        return releaseMaxAge;
    }

    public void setReleaseMaxAge( int releaseMaxAge )
    {
        this.releaseMaxAge = releaseMaxAge;
    }

    public int getSnapshotMaxAge()
    {
        return snapshotMaxAge;
    }

    public void setSnapshotMaxAge( int snapshotMaxAge )
    {
        this.snapshotMaxAge = snapshotMaxAge;
    }

    public int getMetadataMaxAge()
    {
        return metadataMaxAge;
    }

    public void setMetadataMaxAge( int metadataMaxAge )
    {
        this.metadataMaxAge = metadataMaxAge;
    }

    public abstract boolean shouldServeByPolicies( RepositoryRequest request );

    public void storeItemWithChecksums( ResourceStoreRequest request, InputStream is, Map<String, String> userAttributes )
        throws UnsupportedStorageOperationException,
            IllegalOperationException,
            StorageException,
            AccessDeniedException
    {
        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "storeItemWithChecksums() :: " + request.getRequestPath() );
        }

        getArtifactStoreHelper().storeItemWithChecksums( request, is, userAttributes );
    }

    public void deleteItemWithChecksums( ResourceStoreRequest request )
        throws UnsupportedStorageOperationException,
            IllegalOperationException,
            ItemNotFoundException,
            StorageException,
            AccessDeniedException
    {
        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "deleteItemWithChecksums() :: " + request.getRequestPath() );
        }

        getArtifactStoreHelper().deleteItemWithChecksums( request );
    }

    public void storeItemWithChecksums( AbstractStorageItem item )
        throws UnsupportedStorageOperationException,
            IllegalOperationException,
            StorageException
    {
        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "storeItemWithChecksums() :: " + item.getRepositoryItemUid().toString() );
        }

        getArtifactStoreHelper().storeItemWithChecksums( item );
    }

    public void deleteItemWithChecksums( RepositoryRequest request )
        throws UnsupportedStorageOperationException,
            IllegalOperationException,
            ItemNotFoundException,
            StorageException
    {
        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "deleteItemWithChecksums() :: " + request.toString() );
        }

        getArtifactStoreHelper().deleteItemWithChecksums( request );
    }

    public MetadataManager getMetadataManager()
    {
        return metadataManager;
    }

    // =================================================================================
    // DefaultRepository customizations

    @Override
    protected StorageItem doRetrieveItem( RepositoryRequest request )
        throws IllegalOperationException,
            ItemNotFoundException,
            StorageException
    {
        if ( !shouldServeByPolicies( request ) )
        {
            if ( getLogger().isDebugEnabled() )
            {
                getLogger().debug(
                    "The serving of item " + request.toString() + " is forbidden by Maven repository policy." );
            }

            throw new ItemNotFoundException( request );
        }

        return super.doRetrieveItem( request );
    }

    @Override
    public void storeItem( StorageItem item )
        throws UnsupportedStorageOperationException,
            IllegalOperationException,
            StorageException
    {
        if ( shouldServeByPolicies( new RepositoryRequest( this, new ResourceStoreRequest( item ) ) ) )
        {
            super.storeItem( item );
        }
        else
        {
            String msg = "Storing of item " + item.getRepositoryItemUid().toString()
                + " is forbidden by Maven Repository policy. Because " + getId() + " is a "
                + getRepositoryPolicy().name() + " repository";

            getLogger().info( msg );

            throw new UnsupportedStorageOperationException( msg );
        }
    }

    @Override
    public boolean isCompatible( Repository repository )
    {
        if ( super.isCompatible( repository ) && MavenRepository.class.isAssignableFrom( repository.getClass() )
            && getRepositoryPolicy().equals( ( (MavenRepository) repository ).getRepositoryPolicy() ) )
        {
            return true;
        }

        return false;
    }

    // =================================================================================
    // DefaultRepository customizations

    @Override
    protected AbstractStorageItem doRetrieveRemoteItem( RepositoryRequest request )
        throws ItemNotFoundException,
            RemoteAccessException,
            StorageException
    {
        if ( !isChecksum( request.getResourceStoreRequest().getRequestPath() ) )
        {
            // we are about to download an artifact from remote repository
            // lets clean any existing (stale) checksum files
            removeLocalChecksum( request.getResourceStoreRequest() );
        }

        return super.doRetrieveRemoteItem( request );
    }

    @Override
    protected boolean doValidateRemoteItemContent( String baseUrl, AbstractStorageItem item,
        List<NexusArtifactEvent> events )
        throws StorageException
    {
        if ( isChecksum( item.getRepositoryItemUid().getPath() ) )
        {
            // do not validate checksum files
            return true;
        }

        if ( getChecksumPolicy() == null || !getChecksumPolicy().shouldCheckChecksum()
            || !( item instanceof DefaultStorageFileItem ) )
        {
            // there is either no need to validate or we can't validate the item content
            return true;
        }

        RepositoryItemUid uid = item.getRepositoryItemUid();

        ResourceStoreRequest request = new ResourceStoreRequest( item );

        DefaultStorageFileItem hashItem = null;

        // we prefer SHA1 ...
        try
        {
            request.pushRequestPath( uid.getPath() + ".sha1" );

            hashItem = doRetriveRemoteChecksumItem( request );
        }
        catch ( ItemNotFoundException sha1e )
        {
            // ... but MD5 will do too
            try
            {
                request.popRequestPath();

                request.pushRequestPath( uid.getPath() + ".md5" );

                hashItem = doRetriveRemoteChecksumItem( request );
            }
            catch ( ItemNotFoundException md5e )
            {
                getLogger().debug( "Item checksums (SHA1, MD5) remotely unavailable " + uid.toString() );
            }
        }

        String remoteHash = null;

        if ( hashItem != null )
        {
            // store checksum file locally
            hashItem = (DefaultStorageFileItem) doCacheItem( hashItem );

            // read checksum
            try
            {
                InputStream hashItemContent = hashItem.getInputStream();

                try
                {
                    remoteHash = StringUtils.chomp( IOUtil.toString( hashItemContent ) ).trim().split( " " )[0];
                }
                finally
                {
                    IOUtil.close( hashItemContent );
                }
            }
            catch ( IOException e )
            {
                getLogger().warn( "Cannot read hash string for remotely fetched StorageFileItem: " + uid.toString(), e );
            }
        }

        // let compiler make sure I did not forget to populate validation results
        String msg;
        boolean contentValid;

        if ( remoteHash == null && ChecksumPolicy.STRICT.equals( getChecksumPolicy() ) )
        {
            msg = "The artifact " + item.getPath() + " has no remote checksum in repository " + item.getRepositoryId()
                + "! The checksumPolicy of repository forbids downloading of it.";

            contentValid = false;
        }
        else if ( hashItem == null )
        {
            msg = "Warning, the artifact " + item.getPath() + " has no remote checksum in repository "
                + item.getRepositoryId() + "!";

            contentValid = true; // policy is STRICT_IF_EXIST or WARN
        }
        else
        {
            String hashKey = hashItem.getPath().endsWith( ".sha1" )
                ? DigestCalculatingInspector.DIGEST_SHA1_KEY
                : DigestCalculatingInspector.DIGEST_MD5_KEY;

            if ( remoteHash != null && remoteHash.equals( item.getAttributes().get( hashKey ) ) )
            {
                // remote hash exists and matches item content
                return true;
            }

            if ( ChecksumPolicy.WARN.equals( getChecksumPolicy() ) )
            {
                msg = "Warning, the artifact " + item.getPath()
                    + " and it's remote checksums does not match in repository " + item.getRepositoryId() + "!";

                contentValid = true;
            }
            else
            // STRICT or STRICT_IF_EXISTS
            {
                msg = "The artifact " + item.getPath() + " and it's remote checksums does not match in repository "
                    + item.getRepositoryId() + "! The checksumPolicy of repository forbids downloading of it.";

                contentValid = false;
            }
        }

        events.add( newChechsumFailureEvent( item, msg ) );

        if ( !contentValid && hashItem != null )
        {
            // TODO should we remove bad checksum if policy==WARN?
            try
            {
                getLocalStorage().deleteItem(
                    this,
                    new ResourceStoreRequest( hashItem.getRepositoryItemUid().getPath(), true ) );
            }
            catch ( ItemNotFoundException e )
            {
                // ignore
            }
            catch ( UnsupportedStorageOperationException e )
            {
                // huh?
            }
        }

        return contentValid;
    }

    /**
     * Special implementation of doRetrieveRemoteItem that treats all exceptions as ItemNotFoundException. To be used
     * form #doValidateRemoteItemContent only!
     */
    private DefaultStorageFileItem doRetriveRemoteChecksumItem( ResourceStoreRequest request )
        throws ItemNotFoundException
    {
        try
        {
            return (DefaultStorageFileItem) getRemoteStorage().retrieveItem( this, request, getRemoteUrl() );
        }
        catch ( RemoteAccessException e )
        {
            throw new ItemNotFoundException( request.getRequestPath(), e );
        }
        catch ( StorageException e )
        {
            throw new ItemNotFoundException( request.getRequestPath(), e );
        }
    }

    private NexusArtifactEvent newChechsumFailureEvent( AbstractStorageItem item, String msg )
    {
        NexusArtifactEvent nae = new NexusArtifactEvent();

        nae.setAction( NexusArtifactEvent.ACTION_BROKEN_WRONG_REMOTE_CHECKSUM );

        nae.setEventDate( new Date() );

        nae.setEventContext( item.getItemContext() );

        nae.setMessage( msg );

        NexusItemInfo ai = new NexusItemInfo();

        ai.setPath( item.getPath() );

        ai.setRepositoryId( item.getRepositoryId() );

        ai.setRemoteUrl( item.getRemoteUrl() );

        nae.setNexusItemInfo( ai );

        return nae;
    }

    private boolean isChecksum( String path )
    {
        return path.endsWith( ".sha1" ) || path.endsWith( ".md5" );
    }

    private void removeLocalChecksum( ResourceStoreRequest request )
        throws StorageException
    {
        try
        {
            request.pushRequestPath( request.getRequestPath() + ".sha1" );

            try
            {
                getLocalStorage().deleteItem( this, request );
            }
            catch ( ItemNotFoundException e )
            {
                // this is exactly what we're trying to achieve
            }

            request.popRequestPath();

            request.pushRequestPath( request.getRequestPath() + ".md5" );

            try
            {
                getLocalStorage().deleteItem( this, request );
            }
            catch ( ItemNotFoundException e )
            {
                // this is exactly what we're trying to achieve
            }

            request.popRequestPath();
        }
        catch ( UnsupportedStorageOperationException e )
        {
            // huh?
        }

    }

    @Override
    protected void markItemRemotelyChecked( RepositoryRequest request )
        throws StorageException,
            ItemNotFoundException
    {
        super.markItemRemotelyChecked( request );

        request
            .getResourceStoreRequest().pushRequestPath( request.getResourceStoreRequest().getRequestPath() + ".sha1" );

        if ( getLocalStorage().containsItem( this, request.getResourceStoreRequest() ) )
        {
            super.markItemRemotelyChecked( request );
        }

        request.getResourceStoreRequest().popRequestPath();

        request.getResourceStoreRequest().pushRequestPath( request.getResourceStoreRequest().getRequestPath() + ".md5" );

        if ( getLocalStorage().containsItem( this, request.getResourceStoreRequest() ) )
        {
            super.markItemRemotelyChecked( request );
        }

        request.getResourceStoreRequest().popRequestPath();
    }

    @Override
    public void deleteItem( RepositoryRequest request )
        throws UnsupportedStorageOperationException,
            IllegalOperationException,
            ItemNotFoundException,
            StorageException
    {
        // first determine from where to rebuild metadata
        String path = RepositoryItemUid.PATH_ROOT;

        StorageItem item = getLocalStorage().retrieveItem( this, request.getResourceStoreRequest() );

        if ( item instanceof StorageCollectionItem )
        {
            path = ItemPathUtils.getParentPath( item.getPath() );
        }
        else if ( item instanceof StorageFileItem )
        {
            path = ItemPathUtils.getParentPath( ItemPathUtils.getParentPath( item.getPath() ) );
        }

        // then delete the item
        super.deleteItem( request );

        // finally rebuild metadata
        recreateMavenMetadata( new ResourceStoreRequest( path, true ) );
    }
}
