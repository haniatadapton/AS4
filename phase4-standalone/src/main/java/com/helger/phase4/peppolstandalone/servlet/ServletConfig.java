/*
 * Copyright (C) 2023-204 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phase4.peppolstandalone.servlet;

import java.io.File;
import java.security.KeyStore;

import javax.annotation.Nonnull;
//import java.security.cert.X509Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.helger.commons.debug.GlobalDebug;
import com.helger.commons.exception.InitializationException;
import com.helger.commons.http.EHttpMethod;
import com.helger.commons.mime.CMimeType;
import com.helger.commons.string.StringHelper;
import com.helger.httpclient.HttpDebugger;
import com.helger.phase4.config.AS4Configuration;
import com.helger.phase4.crypto.AS4CryptoFactoryProperties;
import com.helger.phase4.crypto.IAS4CryptoFactory;
import com.helger.phase4.dump.AS4DumpManager;
import com.helger.phase4.dump.AS4IncomingDumperFileBased;
import com.helger.phase4.dump.AS4OutgoingDumperFileBased;
import com.helger.phase4.incoming.AS4RequestHandler;
import com.helger.phase4.incoming.AS4ServerInitializer;
import com.helger.phase4.incoming.mgr.AS4ProfileSelector;
import com.helger.phase4.profile.bdew.AS4BDEWProfileRegistarSPI;
import com.helger.phase4.servlet.AS4UnifiedResponse;
import com.helger.phase4.servlet.AS4XServletHandler;
import com.helger.phase4.servlet.IAS4ServletRequestHandlerCustomizer;
import com.helger.photon.io.WebFileIO;
import com.helger.servlet.ServletHelper;
import com.helger.web.scope.IRequestWebScopeWithoutResponse;
import com.helger.web.scope.mgr.WebScopeManager;
import com.helger.xservlet.AbstractXServlet;
import com.helger.xservlet.requesttrack.RequestTrackerSettings;

import jakarta.activation.CommandMap;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.ServletContext;

@Configuration
public class ServletConfig
{
  private static final Logger LOGGER = LoggerFactory.getLogger(ServletConfig.class);

  @Nonnull
  public static IAS4CryptoFactory getCryptoFactoryToUse()
  {
    final IAS4CryptoFactory ret = AS4CryptoFactoryProperties.getDefaultInstance();
    return ret;
  }

  public static class MyAS4Servlet extends AbstractXServlet
  {
    public MyAS4Servlet()
    {
      settings().setMultipartEnabled(false);
      final AS4XServletHandler hdl = new AS4XServletHandler();
      hdl.setRequestHandlerCustomizer(new IAS4ServletRequestHandlerCustomizer()
      {
        public void customizeBeforeHandling(@Nonnull final IRequestWebScopeWithoutResponse aRequestScope,
                                          @Nonnull final AS4UnifiedResponse aUnifiedResponse,
                                          @Nonnull final AS4RequestHandler aRequestHandler)
        {
          aRequestHandler.setCryptoFactory(ServletConfig.getCryptoFactoryToUse());
        }

        public void customizeAfterHandling(@Nonnull final IRequestWebScopeWithoutResponse aRequestScope,
                                         @Nonnull final AS4UnifiedResponse aUnifiedResponse,
                                         @Nonnull final AS4RequestHandler aRequestHandler)
        {}
      });
      handlerRegistry().registerHandler(EHttpMethod.POST, hdl);
    }
  }

  @Bean
  public ServletRegistrationBean<MyAS4Servlet> servletRegistrationBean(final ServletContext ctx)
  {
    _init(ctx);
    final ServletRegistrationBean<MyAS4Servlet> bean = new ServletRegistrationBean<>(new MyAS4Servlet(),
                                                                                   true,
                                                                                   "/as4");
    bean.setLoadOnStartup(1);
    return bean;
  }

  private void _init(@Nonnull final ServletContext aSC)
  {
    if (!WebScopeManager.isGlobalScopePresent())
    {
      WebScopeManager.onGlobalBegin(aSC);
      _initGlobalSettings(aSC);
      _initAS4();
      _initCrypto();
    }
  }

  private static void _initGlobalSettings(@Nonnull final ServletContext aSC)
  {
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();

    if (GlobalDebug.isDebugMode())
    {
      RequestTrackerSettings.setLongRunningRequestsCheckEnabled(false);
      RequestTrackerSettings.setParallelRunningRequestsCheckEnabled(false);
    }

    HttpDebugger.setEnabled(false);

    if (CommandMap.getDefaultCommandMap().createDataContentHandler(CMimeType.MULTIPART_RELATED.getAsString()) == null)
    {
      throw new IllegalStateException("No DataContentHandler for MIME Type '" +
                                    CMimeType.MULTIPART_RELATED.getAsString() +
                                    "' is available. There seems to be a problem with the dependencies/packaging");
    }

    final String sServletContextPath = ServletHelper.getServletContextBasePath(aSC);
    final String sDataPath = AS4Configuration.getDataPath();
    if (StringHelper.hasNoText(sDataPath))
      throw new InitializationException("No data path was provided!");
    final boolean bFileAccessCheck = false;
    WebFileIO.initPaths(new File(sDataPath).getAbsoluteFile(), sServletContextPath, bFileAccessCheck);
  }

  private static void _initAS4()
  {
    AS4ProfileSelector.setCustomAS4ProfileID(AS4BDEWProfileRegistarSPI.AS4_PROFILE_ID);
    AS4ServerInitializer.initAS4Server();
    AS4DumpManager.setIncomingDumper(new AS4IncomingDumperFileBased());
    AS4DumpManager.setOutgoingDumper(new AS4OutgoingDumperFileBased());
  }

  private static void _initCrypto()
  {
    final KeyStore aKS = AS4CryptoFactoryProperties.getDefaultInstance().getKeyStore();
    if (aKS == null)
      throw new InitializationException("Failed to load configured AS4 Key store - fix the configuration");
    LOGGER.info("Successfully loaded configured AS4 key store from the crypto factory");

    final KeyStore.PrivateKeyEntry aPKE = AS4CryptoFactoryProperties.getDefaultInstance().getPrivateKeyEntry();
    if (aPKE == null)
      throw new InitializationException("Failed to load configured AS4 private key - fix the configuration");
    LOGGER.info("Successfully loaded configured AS4 private key from the crypto factory");
  }

  private static final class Destroyer
  {
    @PreDestroy
    public void destroy()
    {
      if (WebScopeManager.isGlobalScopePresent())
      {
        AS4ServerInitializer.shutdownAS4Server();
        WebFileIO.resetPaths();
        WebScopeManager.onGlobalEnd();
      }
    }
  }

  @Bean
  public Destroyer destroyer()
  {
    return new Destroyer();
  }
}
