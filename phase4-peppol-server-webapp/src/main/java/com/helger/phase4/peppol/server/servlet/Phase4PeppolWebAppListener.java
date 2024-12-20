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

  private static void _initAS4 ()
  {
    // Enforce Peppol profile usage
    AS4ProfileSelector.setCustomDefaultAS4ProfileID (AS4PeppolProfileRegistarSPI.AS4_PROFILE_ID);

    // Start duplicate check
    AS4ServerInitializer.initAS4Server ();

    // Store the incoming file as is
    AS4DumpManager.setIncomingDumper (new AS4IncomingDumperFileBased ( (aMessageMetadata,
                                                                        aHttpHeaderMap) -> StorageHelper.getStorageFile (aMessageMetadata,
                                                                                                                         ".as4in"))
    {
      @Override
      public void onEndRequest (@Nonnull final IAS4IncomingMessageMetadata aMessageMetadata,
                                @Nullable final Exception aCaughtException)
      {
        // Save the metadata also to a file
        final File aFile = StorageHelper.getStorageFile (aMessageMetadata, ".metadata");
        if (SimpleFileIO.writeFile (aFile,
                                    AS4IncomingHelper.getIncomingMetadataAsJson (aMessageMetadata)
                                                     .getAsJsonString (JsonWriterSettings.DEFAULT_SETTINGS_FORMATTED),
                                    StandardCharsets.UTF_8).isFailure ())
          LOGGER.error ("Failed to write metadata to '" + aFile.getAbsolutePath () + "'");
        else
          LOGGER.info ("Wrote metadata to '" + aFile.getAbsolutePath () + "'");
      }
    });

    // Store the outgoings file as well
    AS4DumpManager.setOutgoingDumper (new AS4OutgoingDumperFileBased ( (eMsgMode, sMessageID, nTry) -> StorageHelper
                                                                                                                    .getStorageFile (sMessageID,
                                                                                                                                     nTry,
                                                                                                                                     ".as4out")));
  }

  private static void _initPeppolAS4 ()
  {
    // Our server expects all SBDH to contain the COUNTRY_C1 element in SBDH
    // (this is the default setting, but added it here for easy modification)
    // this used to be true, but we don't need it for local testing
    //Phase4PeppolDefaultReceiverConfiguration.setCheckSBDHForMandatoryCountryC1(true);
    Phase4PeppolDefaultReceiverConfiguration.setCheckSBDHForMandatoryCountryC1(false);

    // Our server should check all signing certificates of incoming messages if
    // they are revoked or not
    // (this is the default setting, but added it here for easy modification)
    // this used to be true, but we don't need it for local testing
    //Phase4PeppolDefaultReceiverConfiguration.setCheckSigningCertificateRevocation(true);

    Phase4PeppolDefaultReceiverConfiguration.setCheckSigningCertificateRevocation(false);
    
    // Disable signing certificate validation for testing
    //Phase4PeppolServletConfiguration.setCheckSigningCertificate(false);

    // Make sure the download of CRL is using Apache HttpClient and that the
    // provided settings are used. If e.g. a proxy is needed to access outbound
    // resources, it can be configured here
    final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
    if (false)
      aHCS.setProxyHost (new HttpHost (AS4Configuration.getConfig ().getAsString ("http.proxy.host"),
                                       AS4Configuration.getConfig ().getAsInt ("http.proxy.port")));
    PeppolCRLDownloader.setAsDefaultCRLCache (aHCS);

    // Throws an exception if configuration parameters are missing
    final AS4CryptoFactoryConfiguration aCF = AS4CryptoFactoryConfiguration.getDefaultInstance();

    // Add BouncyCastle provider if not already added
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
    }

    // Configure crypto algorithms through configuration properties
    System.setProperty("phase4.crypto.properties.signaturealgorithm", ECryptoAlgorithmSign.ECDSA_SHA_256.getID());
    System.setProperty("phase4.crypto.properties.encryptionalgorithm", ECryptoAlgorithmCrypt.AES_128_GCM.getID());

    // The keystore type should be configured in crypto.properties

    // Check if crypto properties are okay - fail early if something is
    // misconfigured
    LOGGER.info ("Trying to load configured key store (type=" +
                 aCF.getKeyStoreDescriptor ().getKeyStoreType () +
                 ", path=" +
                 aCF.getKeyStoreDescriptor ().getKeyStorePath () +
                 ")");
    final KeyStore aKS = aCF.getKeyStore ();
    if (aKS == null)
      throw new InitializationException ("Failed to load configured Keystore");
    LOGGER.info ("  Successfully loaded configured key store from the crypto factory.");
    LOGGER.info ("  Loaded key store type is '" + aKS.getType () + "'");

    // List all the aliases - debug only
    try
    {
      final ICommonsList <String> aAliases = CollectionHelper.newList (aKS.aliases ());
      LOGGER.info ("The keystore contains the following " + aAliases.size () + " alias(es): " + aAliases);
      int nIndex = 1;
      for (final String sAlias : aAliases)
      {
        String sType = "unknown";
        try
        {
          final KeyStore.Entry aEntry = aKS.getEntry (sAlias,
                                                      new KeyStore.PasswordProtection (aCF.getKeyStoreDescriptor ()
                                                                                          .getKeyPassword ()));
          if (aEntry instanceof PrivateKeyEntry)
            sType = "private-key";
          else
            if (aEntry instanceof KeyStore.SecretKeyEntry)
              sType = "secret-key";
            else
              if (aEntry instanceof KeyStore.TrustedCertificateEntry)
                sType = "trusted-certificate";
        }
        catch (final Exception ex)
        {
          // Ignore
        }
        LOGGER.info ("  " +
                     nIndex +
                     ".: alias(" +
                     sAlias +
                     ") type(" +
                     sType +
                     ") date(" +
                     aKS.getCreationDate (sAlias) +
                     ")");
        ++nIndex;
      }
    }
    catch (final GeneralSecurityException ex)
    {
      LOGGER.error ("Failed to list all aliases in the keystore", ex);
    }

    // Check if the key configuration is okay - fail early if something is
    // misconfigured
    LOGGER.info ("Trying to load configured private key (alias=" + aCF.getKeyAlias () + ")");
    final PrivateKeyEntry aPKE = aCF.getPrivateKeyEntry ();
    if (aPKE == null)
      throw new InitializationException ("Failed to load configured private key");
    LOGGER.info ("  Successfully loaded configured private key from the crypto factory");

    final X509Certificate aAPCert = (X509Certificate) aPKE.getCertificate ();

    // Try the reverse search in the key store - debug only
    try
    {
      final String sAlias = aKS.getCertificateAlias (aAPCert);
      LOGGER.info ("  The reverse search of the certificate lead to alias '" + sAlias + "'");
    }
    catch (final GeneralSecurityException ex)
    {
      LOGGER.error ("Failed to do a reverse search of the certificate", ex);
    }

    // Check if the certificate is really a Peppol AP certificate - fail early
    // if something is misconfigured
    // * Do not cache result
    // Disable Peppol certificate validation for local testing
    if (AS4Configuration.getConfig().getAsBoolean("phase4.peppol.validation.disabled", false)) {
      LOGGER.warn("Peppol certificate validation is disabled - FOR TESTING ONLY!");
      Phase4PeppolDefaultReceiverConfiguration.setReceiverCheckEnabled(false);
      return;
    }
    // * Use the global checking mode or provide a new one
    final EPeppolCertificateCheckResult eCheckResult = PeppolCertificateChecker.checkPeppolAPCertificate (aAPCert,
                                                                                                          MetaAS4Manager.getTimestampMgr ()
                                                                                                                        .getCurrentDateTime (),
                                                                                                          ETriState.FALSE,
                                                                                                          null);
    if (eCheckResult.isInvalid ())
      throw new InitializationException ("The provided certificate is not a valid Peppol AP certificate. Check result: " +
                                         eCheckResult);
    LOGGER.info ("Successfully checked that the provided certificate is a valid Peppol AP certificate.");

    // Enable or disable, if upon receival, the received should be checked or
    // not
    final String sSMPURL = AS4Configuration.getConfig ().getAsString ("smp.url");
    final String sAPURL = AS4Configuration.getThisEndpointAddress ();
    if (StringHelper.hasText (sSMPURL) && StringHelper.hasText (sAPURL))
    {
      Phase4PeppolDefaultReceiverConfiguration.setReceiverCheckEnabled (true);
      Phase4PeppolDefaultReceiverConfiguration.setSMPClient (new SMPClientReadOnly (URLHelper.getAsURI (sSMPURL)));
      Phase4PeppolDefaultReceiverConfiguration.setWildcardSelectionMode (Phase4PeppolDefaultReceiverConfiguration.DEFAULT_WILDCARD_SELECTION_MODE);
      Phase4PeppolDefaultReceiverConfiguration.setAS4EndpointURL (sAPURL);
      Phase4PeppolDefaultReceiverConfiguration.setAPCertificate (aAPCert);
      LOGGER.info (CAS4.LIB_NAME +
                   " Peppol receiver checks are enabled on SMP '" +
                   sSMPURL +
                   "' and AP '" +
                   sAPURL +
                   "'");
    }
    else
    {
      Phase4PeppolDefaultReceiverConfiguration.setReceiverCheckEnabled (false);
      LOGGER.warn (CAS4.LIB_NAME + " Peppol receiver checks are disabled");
    }
  }

  @Override
  protected void initManagers ()
  {
    _initAS4 ();
    _initPeppolAS4 ();
  }

  @Override
  protected void beforeContextDestroyed (@Nonnull final ServletContext aSC)
  {
    AS4ServerInitializer.shutdownAS4Server ();
  }
}
