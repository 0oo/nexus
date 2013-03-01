/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
/*global define*/
define('ext/util/CSS', ['extjs'], function(Ext) {
  var origFn = Ext.util.CSS.createStyleSheet;
  Ext.util.CSS.createStyleSheet = function(cssText, id) {
    // HACK
    // IE9 is apparently doing "the right thing" in some circumstances. If original function fails,
    // retry with the generic approach.
    if (Ext.isIE9) {
      // COPYPASTED FROM EXTJS 3.4.1
      var ss, head = document.getElementsByTagName("head")[0], rules = document.createElement("style");
      rules.setAttribute("type", "text/css");
      if (id) {
        rules.setAttribute("id", id);
      }

      try {
        rules.appendChild(document.createTextNode(cssText));
      }
      catch (error) {
        rules.cssText = cssText;
      }
      head.appendChild(rules);
      ss = rules.styleSheet || (rules.sheet || document.styleSheets[document.styleSheets.length - 1]);

      this.cacheStyleSheet(ss);
      return ss;
    }

    origFn.apply(this, arguments);
  };
});