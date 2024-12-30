/*
 * Copyright (C) 2020-2024 Philip Helger (www.helger.com)
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
package com.helger.phase4.peppol.server.servlet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.helger.phase4.crypto.ECryptoAlgorithmCrypt;
import com.helger.phase4.crypto.ECryptoAlgorithmSign;
import org.apache.hc.core5.http.HttpHost;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.helger.commons.collection.CollectionHelper;
import com.helger.commons.collection.impl.ICommonsList;
import com.helger.commons.debug.GlobalDebug;
import com.helger.commons.exception.InitializationException;
import com.helger.commons.io.file.SimpleFileIO;
import com.helger.commons.mime.CMimeType;
import com.helger.commons.state.ETriState;
import com.helger.commons.string.StringHelper;
import com.helger.commons.url.URLHelper;
import com.helger.httpclient.HttpDebugger;
import com.helger.json.serialize.JsonWriterSettings;
import com.helger.peppol.utils.EPeppolCertificateCheckResult;
import com.helger.peppol.utils.PeppolCertificateChecker;
import com.helger.phase4.CAS4;
import com.helger.phase4.config.AS4Configuration;
import com.helger.phase4.crypto.AS4CryptoFactoryConfiguration;
import com.helger.phase4.dump.AS4DumpManager;
import com.helger.phase4.dump.AS4IncomingDumperFileBased;
import com.helger.phase4.dump.AS4OutgoingDumperFileBased;
import com.helger.phase4.incoming.AS4IncomingHelper;
import com.helger.phase4.incoming.AS4ServerInitializer;
import com.helger.phase4.incoming.IAS4IncomingMessageMetadata;
import com.helger.phase4.incoming.mgr.AS4ProfileSelector;
import com.helger.phase4.mgr.MetaAS4Manager;
import com.helger.phase4.peppol.server.storage.StorageHelper;
import com.helger.phase4.peppol.servlet.Phase4PeppolDefaultReceiverConfiguration;
import com.helger.phase4.profile.peppol.AS4PeppolProfileRegistarSPI;
import com.helger.phase4.profile.peppol.PeppolCRLDownloader;
import com.helger.phase4.profile.peppol.Phase4PeppolHttpClientSettings;
import com.helger.photon.core.servlet.WebAppListener;
import com.helger.photon.security.CSecurity;
import com.helger.photon.security.mgr.PhotonSecurityManager;
import com.helger.photon.security.user.IUserManager;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.xservlet.requesttrack.RequestTrackerSettings;

import jakarta.activation.CommandMap;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.WebListener;

@WebListener
public final class Phase4PeppolWebAppListener extends WebAppListener
{
  private static final Logger LOGGER = LoggerFactory.getLogger (Phase4PeppolWebAppListener.class);

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  @Override
  @Nullable
  protected String getInitParameterDebug (@Nonnull final ServletContext aSC)
  {
    return Boolean.toString (AS4Configuration.isGlobalDebug ());
  }

  @Override
  @Nullable
  protected String getInitParameterProduction (@Nonnull final ServletContext aSC)
  {
    return Boolean.toString (AS4Configuration.isGlobalProduction ());
  }

  @Override
  @Nullable
  protected String getInitParameterNoStartupInfo (@Nonnull final ServletContext aSC)
  {
    return Boolean.toString (AS4Configuration.isNoStartupInfo ());
  }

  @Override
  protected String getDataPath (@Nonnull final ServletContext aSC)
  {
    return AS4Configuration.getDataPath ();
  }

  @Override
  protected boolean shouldCheckFileAccess (@Nonnull final ServletContext aSC)
  {
    return false;
  }

  @Override
  protected void afterContextInitialized (@Nonnull final ServletContext aSC)
  {
    super.afterContextInitialized (aSC);

    // Show registered servlets
    for (final Map.Entry <String, ? extends ServletRegistration> aEntry : aSC.getServletRegistrations ().entrySet ())
      LOGGER.info ("Servlet '" + aEntry.getKey () + "' is mapped to " + aEntry.getValue ().getMappings ());
  }

  @Override
  protected void initGlobalSettings ()
  {
    // Logging: JUL to SLF4J
    SLF4JBridgeHandler.removeHandlersForRootLogger ();
    SLF4JBridgeHandler.install ();

    if (GlobalDebug.isDebugMode ())
    {
      RequestTrackerSettings.setLongRunningRequestsCheckEnabled (false);
      RequestTrackerSettings.setParallelRunningRequestsCheckEnabled (false);
    }

    HttpDebugger.setEnabled (false);

    // Sanity check
    if (CommandMap.getDefaultCommandMap ().createDataContentHandler (CMimeType.MULTIPART_RELATED.getAsString ()) ==
        null)
      throw new IllegalStateException ("No DataContentHandler for MIME Type '" +
                                       CMimeType.MULTIPART_RELATED.getAsString () +
                                       "' is available. There seems to be a problem with the dependencies/packaging");
  }

  @Override
  protected void initSecurity ()
  {
    // Ensure user exists
    final IUserManager aUserMgr = PhotonSecurityManager.getUserMgr ();
    if (!aUserMgr.containsWithID (CSecurity.USER_ADMINISTRATOR_ID))
    {
      aUserMgr.createPredefinedUser (CSecurity.USER_ADMINISTRATOR_ID,
                                     CSecurity.USER_ADMINISTRATOR_LOGIN,
                                     CSecurity.USER_ADMINISTRATOR_EMAIL,
                                     CSecurity.USER_ADMINISTRATOR_PASSWORD,
                                     "Admin",
                                     "istrator",
                                     null,
                                     Locale.US,
                                     null,
                                     false);
    }
  }



  private static void _initBDEWAS4()
  {
    // Add BouncyCastle provider if not already added
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
    }


//    // Configure keystore with absolute path
    String keystorePath = new File("ks2.p12").getAbsolutePath();

//    // Initialize BDEW profile
    try {
        // Ensure BDEW profile is registered and set as default
        AS4ProfileSelector.setCustomDefaultAS4ProfileID("bdew");


        // Check if crypto properties are okay
        final AS4CryptoFactoryConfiguration aCF = AS4CryptoFactoryConfiguration.getDefaultInstance();
        LOGGER.info("Trying to load configured key store from: " + keystorePath);

        final KeyStore aKS = aCF.getKeyStore();
        if (aKS == null)
            throw new InitializationException("Failed to load configured Keystore");

        LOGGER.info("Successfully loaded configured key store from the crypto factory");

      // Start duplicate check
      AS4ServerInitializer.initAS4Server();

      // Store the incoming file as is
      AS4DumpManager.setIncomingDumper(new AS4IncomingDumperFileBased((aMessageMetadata,
                                                                       aHttpHeaderMap) -> StorageHelper.getStorageFile(aMessageMetadata,
              ".as4in")) {
        @Override
        public void onEndRequest(@Nonnull final IAS4IncomingMessageMetadata aMessageMetadata,
                                 @Nullable final Exception aCaughtException) {
          // Save the metadata also to a file
          final File aFile = StorageHelper.getStorageFile(aMessageMetadata, ".metadata");
          if (SimpleFileIO.writeFile(aFile,
                  AS4IncomingHelper.getIncomingMetadataAsJson(aMessageMetadata)
                          .getAsJsonString(JsonWriterSettings.DEFAULT_SETTINGS_FORMATTED),
                  StandardCharsets.UTF_8).isFailure())
            LOGGER.error("Failed to write metadata to '" + aFile.getAbsolutePath() + "'");
          else
            LOGGER.info("Wrote metadata to '" + aFile.getAbsolutePath() + "'");
        }
      });

      // Store the outgoings file as well
      AS4DumpManager.setOutgoingDumper(new AS4OutgoingDumperFileBased((eMsgMode, sMessageID, nTry) -> StorageHelper
              .getStorageFile(sMessageID,
                      nTry,
                      ".as4out")));

    } catch (Exception ex) {
        LOGGER.error("Failed to initialize BDEW profile", ex);
        throw new InitializationException("Failed to initialize BDEW profile", ex);
    }
  }

  @Override
  protected void initManagers ()
  {
    _initBDEWAS4 ();
  }

  @Override
  protected void beforeContextDestroyed (@Nonnull final ServletContext aSC)
  {
    AS4ServerInitializer.shutdownAS4Server ();
  }
}
