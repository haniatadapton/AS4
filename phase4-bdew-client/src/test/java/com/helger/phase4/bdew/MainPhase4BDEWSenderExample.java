/*
 * Copyright (C) 2023-2024 Gregor Scholtysik (www.soptim.de)
 * gregor[dot]scholtysik[at]soptim[dot]de
 *
 * Copyright (C) 2023-2024 Philip Helger (www.helger.com)
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
package com.helger.phase4.bdew;

import com.helger.commons.datetime.PDTFactory;
import com.helger.commons.wrapper.Wrapper;
import com.helger.phase4.CAS4;
import com.helger.phase4.attachment.AS4OutgoingAttachment;
import com.helger.phase4.bdew.Phase4BDEWSender.BDEWPayloadParams;
import com.helger.phase4.crypto.AS4CryptoFactoryInMemoryKeyStore;
import com.helger.phase4.crypto.ECryptoKeyIdentifierType;
import com.helger.phase4.dump.AS4DumpManager;
import com.helger.phase4.dump.AS4IncomingDumperFileBased;
import com.helger.phase4.dump.AS4OutgoingDumperFileBased;
import com.helger.phase4.ebms3header.Ebms3SignalMessage;
import com.helger.phase4.sender.EAS4UserMessageSendResult;
import com.helger.security.keystore.EKeyStoreType;
import com.helger.security.keystore.KeyStoreAndKeyDescriptor;
import com.helger.security.keystore.KeyStoreHelper;
import com.helger.security.keystore.TrustStoreDescriptor;
import com.helger.servlet.mock.MockServletContext;
import com.helger.web.scope.mgr.WebScopeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

public class MainPhase4BDEWSenderExample
{
  private static final Logger LOGGER = LoggerFactory.getLogger (MainPhase4BDEWSenderExample.class);

  private static final String KEYSTORE_PATH = "C:/O/OneDrive - adapton AG/Desktop/Sender (client)/phase4-bdew-client/src/test/resources/ks1.p12";
  private static final String KEYSTORE_PASSWORD = "changeit";
  private static final String KEY_ALIAS = "cert1";
  public static void main (final String [] args)
  {

    // Create scope for global variables that can be shut down gracefully
    WebScopeManager.onGlobalBegin (MockServletContext.create ());

    // Required for "http" only connections
    //GlobalDebug.setDebugModeDirect (true);

    // Optional dump (for debugging purpose only)
    AS4DumpManager.setIncomingDumper (new AS4IncomingDumperFileBased ());
    AS4DumpManager.setOutgoingDumper (new AS4OutgoingDumperFileBased ());

    try
    {
      // Load your keystore
      final KeyStore aKS = KeyStoreHelper.loadKeyStoreDirect(EKeyStoreType.PKCS12,
      KEYSTORE_PATH,
      KEYSTORE_PASSWORD.toCharArray());
      if (aKS == null) {
        LOGGER.error("Failed to load keystore from path: {}", KEYSTORE_PATH);
        throw new IllegalStateException("Failed to load keystore");
      }



      // Verify the key alias exists
      if (!aKS.containsAlias(KEY_ALIAS)) {
        LOGGER.error("Keystore does not contain alias: {}", KEY_ALIAS);
        throw new IllegalStateException("Key alias not found in keystore");
      }

      // Load truststore
      final KeyStore aTrustStore = KeyStoreHelper.loadKeyStoreDirect(EKeyStoreType.PKCS12,
          "src/test/resources/truststore.p12",
              KEYSTORE_PASSWORD.toCharArray());
      if (aTrustStore == null)
        throw new IllegalStateException("Failed to load truststore");

//      // Load receiver's keystore and get their certificate
//      final KeyStore aReceiverKS = KeyStoreHelper.loadKeyStoreDirect(EKeyStoreType.PKCS12,
//          "src/test/resources/ks2.p12",
//          KEYSTORE_PASSWORD.toCharArray());
//      if (aReceiverKS == null) {
//          LOGGER.error("Failed to load receiver keystore");
//          throw new IllegalStateException("Failed to load receiver keystore");
//      }
//
//      // Get receiver's certificate
//      final X509Certificate aReceiverCert = (X509Certificate) aReceiverKS.getCertificate("cert2");
//      if (aReceiverCert == null) {
//          LOGGER.error("Failed to find receiver certificate with alias 'cert2'");
//          throw new IllegalStateException("Failed to find receiver certificate");
//      }
//
//      // Load and verify truststore
//      final KeyStore trustStore = KeyStoreHelper.loadKeyStoreDirect(EKeyStoreType.PKCS12,
//          "src/test/resources/truststore.p12",
//          "changeit".toCharArray());
//
//      // Debug: Print certificates in truststore
//      java.util.Enumeration<String> aliases = trustStore.aliases();
//      LOGGER.info("Certificates in truststore:");
//      while (aliases.hasMoreElements()) {
//          String alias = aliases.nextElement();
//          Certificate cert = trustStore.getCertificate(alias);
//          if (cert instanceof X509Certificate) {
//              X509Certificate x509 = (X509Certificate) cert;
//              LOGGER.info("Alias: " + alias);
//              LOGGER.info("  Subject: " + x509.getSubjectX500Principal());
//              LOGGER.info("  Issuer: " + x509.getIssuerX500Principal());
//              LOGGER.info("  Valid from: " + x509.getNotBefore());
//              LOGGER.info("  Valid until: " + x509.getNotAfter());
//          }
//      }

      // Verify receiver cert is in truststore
//      X509Certificate trustedCert = (X509Certificate) trustStore.getCertificate("cert2");
//      if (trustedCert == null) {
//          LOGGER.error("Receiver certificate not found in truststore");
//          // Import it if needed
//          trustStore.setCertificateEntry("cert2", aReceiverCert);
//          // Save updated truststore
//          try (FileOutputStream fos = new FileOutputStream("src/test/resources/truststore.p12")) {
//              trustStore.store(fos, "changeit".toCharArray());
//          }
//          LOGGER.info("Imported receiver certificate into truststore");
//      }
//
//      // After getting receiver certificate
//      LOGGER.info("Receiver certificate details:");
//      LOGGER.info("  Subject: " + aReceiverCert.getSubjectX500Principal());
//      LOGGER.info("  Issuer: " + aReceiverCert.getIssuerX500Principal());
//      LOGGER.info("  Valid from: " + aReceiverCert.getNotBefore());
//      LOGGER.info("  Valid until: " + aReceiverCert.getNotAfter());

      // Add BouncyCastle as a security provider
      //Security.addProvider(new BouncyCastleProvider());


      final KeyStoreAndKeyDescriptor aKSD = KeyStoreAndKeyDescriptor.builder ()
                .type (EKeyStoreType.PKCS12)
                .path ("src/test/resources/ks1.p12")
                .password (KEYSTORE_PASSWORD)
                .keyAlias (KEY_ALIAS)
                .keyPassword (KEYSTORE_PASSWORD)
                .build ();
      final TrustStoreDescriptor aTSD = TrustStoreDescriptor.builder ()
                .type (EKeyStoreType.PKCS12)
                .path ("src/test/resources/truststore.p12")
                .password (KEYSTORE_PASSWORD)
                .build ();

      // Read XML payload to send
      final byte [] aPayloadBytes = Files.readAllBytes (new File ("src/test/resources/external/examples/base-example.xml").toPath ());
      if (aPayloadBytes == null)
        throw new IllegalStateException ("Failed to read file to be sent");

      final BDEWPayloadParams aBDEWPayloadParams = new BDEWPayloadParams ();
      aBDEWPayloadParams.setDocumentType ("MSCONS");
      aBDEWPayloadParams.setDocumentDate (PDTFactory.getCurrentLocalDate ());
      aBDEWPayloadParams.setDocumentNumber (1234);
      aBDEWPayloadParams.setFulfillmentDate (PDTFactory.getCurrentLocalDate ().minusMonths (2));
      aBDEWPayloadParams.setSubjectPartyId ("Party1");
      aBDEWPayloadParams.setSubjectPartyRole ("MSCONS-AS4-Receiver");

      //final Wrapper <Ebms3SignalMessage> aSignalMsgHolder = new Wrapper <> ();


      // Start configuring here
      final EAS4UserMessageSendResult eResult;
      eResult = Phase4BDEWSender.builder()
                          //Communication Configs
                          .endpointURL("http://localhost:8080/as4")
                          .encryptionKeyIdentifierType(ECryptoKeyIdentifierType.X509_KEY_IDENTIFIER)
                          .signingKeyIdentifierType(ECryptoKeyIdentifierType.BST_DIRECT_REFERENCE)
                          //----PartyInfo----
                          .fromPartyIDType("urn:oasis:names:tc:ebcore:partyid-type:iso6523:0088")
                          .fromPartyID("AS4-Sender")
                          .fromRole(CAS4.DEFAULT_INITIATOR_URL)
                          .toPartyIDType("urn:oasis:names:tc:ebcore:partyid-type:iso6523:0088")
                          .toPartyID("AS4-Receiver")
                          .toRole(CAS4.DEFAULT_RESPONDER_URL)
                          //----PartyInfo----
                          //----CollaborationInfo----
                          .agreementRef("https://www.bdew.de/as4/communication/agreement")
                          .service("https://www.bdew.de/as4/communication/services/MP")
                          .action("http://docs.oasis-open.org/ebxml-msg/as4/200902/action")
                          //----CollaborationInfo----
                          .service("AS4-Service")
                          .action("AS4-Action")
                          .payload(AS4OutgoingAttachment.builder()
                                                         .data(aPayloadBytes)
                                                         .compressionGZIP()
                                                         .mimeTypeXML()
                                                         .charset(StandardCharsets.UTF_8),
                                   aBDEWPayloadParams)
                          .cryptoFactory(new AS4CryptoFactoryInMemoryKeyStore(aKSD, aTSD))
                          //.receiverCertificate(aReceiverCert)
                          //.signalMsgConsumer((aSignalMsg, aMMD, aState) ->
                          //    aSignalMsgHolder.set(aSignalMsg))
                          .sendMessageAndCheckForReceipt();
      LOGGER.info("BDEW send result: " + eResult);




    }
    catch (final Exception ex)
    {
      LOGGER.error("Error sending BDEW message via AS4", ex);
    }
    finally
    {
      WebScopeManager.onGlobalEnd();
    }
  }
}
