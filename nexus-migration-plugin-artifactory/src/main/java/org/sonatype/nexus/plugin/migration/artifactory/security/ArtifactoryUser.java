package org.sonatype.nexus.plugin.migration.artifactory.security;

import java.util.HashSet;
import java.util.Set;

public class ArtifactoryUser
{
    private String username;

    private String email = "";

    private boolean isAdmin = false;

    private Set<ArtifactoryGroup> groups = new HashSet<ArtifactoryGroup>();

    public ArtifactoryUser( String username )
    {
        this.username = username;
    }

    public ArtifactoryUser( String username, String email )
    {
        this.username = username;

        this.email = email;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername( String username )
    {
        this.username = username;
    }

    public String getEmail()
    {
        return email;
    }

    public void setEmail( String email )
    {
        this.email = email;
    }

    public boolean isAdmin()
    {
        return isAdmin;
    }

    public void setAdmin( boolean isAdmin )
    {
        this.isAdmin = isAdmin;
    }

    public Set<ArtifactoryGroup> getGroups()
    {
        return groups;
    }

    @Override
    public boolean equals( Object obj )
    {
        if ( this == obj )
        {
            return true;
        }

        if ( !( obj instanceof ArtifactoryUser ) )
        {
            return false;
        }

        ArtifactoryUser user = (ArtifactoryUser) obj;

        return this.username.equals( user.username ) && this.email.equals( user.email ) && this.isAdmin == user.isAdmin
            && groups.equals( user.groups );
    }
}
