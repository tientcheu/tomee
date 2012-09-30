/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
    * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomee.catalina;

import org.apache.catalina.Context;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.deploy.WebXml;
import org.apache.catalina.startup.ContextConfig;
import org.apache.openejb.assembler.classic.ClassListInfo;
import org.apache.openejb.assembler.classic.WebAppBuilder;
import org.apache.openejb.assembler.classic.WebAppInfo;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.URLs;

import javax.servlet.ServletContainerInitializer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class OpenEJBContextConfig extends ContextConfig {

    private static Logger logger = Logger.getInstance(LogCategory.OPENEJB, OpenEJBContextConfig.class);

    private static final String MYFACES_TOMEEM_CONTAINER_INITIALIZER = "org.apache.tomee.myfaces.TomEEMyFacesContainerInitializer";
    private static final String TOMEE_MYFACES_CONTEXT_LISTENER = "org.apache.tomee.myfaces.TomEEMyFacesContextListener";

    private TomcatWebAppBuilder.StandardContextInfo info;

    // processAnnotationXXX is called for each folder of WEB-INF
    // since we store all classes in WEB-INF we will do it only once so use this boolean to avoid multiple processing
    private boolean webInfClassesAnnotationsProcessed = false;

    public OpenEJBContextConfig(TomcatWebAppBuilder.StandardContextInfo standardContextInfo) {
        logger.debug("OpenEJBContextConfig({0})", standardContextInfo.toString());
        info = standardContextInfo;
    }

    @Override
    protected WebXml createWebXml() {
        String prefix = "";
        if (context instanceof StandardContext) {
            StandardContext standardContext = (StandardContext) context;
            prefix = standardContext.getEncodedPath();
            if (prefix.startsWith("/")) {
                prefix = prefix.substring(1);
            }
        }
        return new OpenEJBWebXml(prefix);
    }

    public class OpenEJBWebXml extends WebXml {
        public static final String OPENEJB_WEB_XML_MAJOR_VERSION_PROPERTY = "openejb.web.xml.major";

        private String prefix;

        public OpenEJBWebXml(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public int getMajorVersion() {
            return SystemInstance.get().getOptions().get(prefix + "." + OPENEJB_WEB_XML_MAJOR_VERSION_PROPERTY,
                    SystemInstance.get().getOptions().get(OPENEJB_WEB_XML_MAJOR_VERSION_PROPERTY, super.getMajorVersion()));
        }
    }

    @Override
    protected void webConfig() {
        // read the real config
        super.webConfig();

        // add myfaces auto-initializer
        try {
            final Class<?> myfacesInitializer = Class.forName(MYFACES_TOMEEM_CONTAINER_INITIALIZER, true, context.getLoader().getClassLoader());
            final ServletContainerInitializer instance = (ServletContainerInitializer) myfacesInitializer.newInstance();
            context.addServletContainerInitializer(instance, getJsfClasses(context));
            context.addApplicationListener(TOMEE_MYFACES_CONTEXT_LISTENER); // cleanup listener
        } catch (Exception ignored) {
            // no-op
        }
    }

    private Set<Class<?>> getJsfClasses(final Context context) {
        final WebAppBuilder builder = SystemInstance.get().getComponent(WebAppBuilder.class);
        final ClassLoader cl = context.getLoader().getClassLoader();
        final Map<String, Set<String>> scanned = builder.getJsfClasses().get(cl);

        if (scanned == null || scanned.isEmpty()) {
            return null;
        }

        final Set<Class<?>> classes = new HashSet<Class<?>>();
        for (Set<String> entry : scanned.values()) {
            for (String name : entry) {
                try {
                    classes.add(cl.loadClass(name));
                } catch (ClassNotFoundException ignored) {
                    logger.warning("class '" + name + "' was found but can't be loaded as a JSF class");
                }
            }
        }

        return classes;
    }

    @Override // called before processAnnotationsFile so using it as hook to init webInfClassesAnnotationsProcessed
    protected void processServletContainerInitializers(final Set<WebXml> fragments) {
        webInfClassesAnnotationsProcessed = false;
        try {
            super.processServletContainerInitializers(fragments);
        } catch (RuntimeException e) { // if exception occurs we have to clear the threadlocal
            webInfClassesAnnotationsProcessed = false;
            throw e;
        }
    }

    @Override // called after processAnnotationsXX so using it as hook to reset webInfClassesAnnotationsProcessed
    protected void processAnnotations(final Set<WebXml> fragments, final boolean handlesTypesOnly) {
        webInfClassesAnnotationsProcessed = false;
        super.processAnnotations(fragments, handlesTypesOnly);
    }


    @Override
    protected void processAnnotationsFile(File file, WebXml fragment, boolean handlesTypesOnly) {
        final WebAppInfo webAppInfo = info.get();
        if (webAppInfo == null) {
            super.processAnnotationsFile(file, fragment, handlesTypesOnly);
            return;
        }

        internalProcessAnnotations(file, webAppInfo, fragment, handlesTypesOnly);
    }

    @Override
    protected void processAnnotationsUrl(URL currentUrl, WebXml fragment, boolean handlesTypeOnly) {
        final File currentUrlAsFile = URLs.toFile(currentUrl);

        final WebAppInfo webAppInfo = info.get();
        if (webAppInfo == null) {
            super.processAnnotationsUrl(currentUrl, fragment, handlesTypeOnly);
            return;
        }

        internalProcessAnnotations(currentUrlAsFile, webAppInfo, fragment, handlesTypeOnly);
    }

    private void internalProcessAnnotations(final File currentUrlAsFile, final WebAppInfo webAppInfo, final WebXml fragment, final boolean  handlesTypeOnly) {
        for (ClassListInfo webAnnotated : webAppInfo.webAnnotatedClasses) {
            try {
                if (!isIncludedIn(webAnnotated.name, currentUrlAsFile)) {
                    continue;
                }

                internalProcessAnnotationsStream(webAnnotated.list, fragment, handlesTypeOnly);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    private void internalProcessAnnotationsStream(final Collection<String> urls, final WebXml fragment, final boolean handlesTypeOnly) {
        for (String url : urls) {
            InputStream is = null;
            try {
                is = new URL(url).openStream();
                processAnnotationsStream(is, fragment, handlesTypeOnly);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            } finally {
                IO.close(is);
            }
        }
    }

    private boolean isIncludedIn(final String filePath, final File classAsFile) throws MalformedURLException {
        final File file = URLs.toFile(new URL(filePath));

        File current = classAsFile;
        boolean webInf = false;
        while (current != null && current.exists()) {
            if (current.equals(file)) {
                final File parent = current.getParentFile();
                if ("classes".equals(current.getName()) && parent != null && "WEB-INF".equals(parent.getName())) {
                    if (webInfClassesAnnotationsProcessed) {
                        return false;
                    }
                    webInfClassesAnnotationsProcessed = true;
                    return true;
                }
                return true;
            }
            if (current.getName().equals("WEB-INF")) {
                webInf = true; // if class loaded from JVM classloader we'll not find it in the war
            }
            current = current.getParentFile();
        }
        return !webInf; // not in the file but not in a war too so use it
    }
}
