package com.jbrisbin.docker

import java.nio.file.{Paths, Files}
import java.security.{KeyStore, KeyFactory}
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext

import org.apache.http.ssl.SSLContexts
import org.bouncycastle.openssl.{PEMKeyPair, PEMParser}

/**
  * @author Jon Brisbin <jbrisbin@basho.com>
  */
private[docker] object SSL {

  def createSSLContext: SSLContext = {
    val dockerCertPath = System.getenv("DOCKER_CERT_PATH")

    val cf: CertificateFactory = CertificateFactory.getInstance("X.509")

    val caPem = Files.newInputStream(Paths.get(dockerCertPath, "ca.pem"))
    val caCert = cf.generateCertificate(caPem)

    val clientPem = Files.newInputStream(Paths.get(dockerCertPath, "cert.pem"))
    val clientCert = cf.generateCertificate(clientPem)

    val clientKeyPairPem = Files.newBufferedReader(Paths.get(dockerCertPath, "key.pem"))
    val clientKeyPair = new PEMParser(clientKeyPairPem).readObject().asInstanceOf[PEMKeyPair]

    val spec = new PKCS8EncodedKeySpec(clientKeyPair.getPrivateKeyInfo.getEncoded())
    val kf = KeyFactory.getInstance("RSA")
    val clientKey = kf.generatePrivate(spec)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(null, null)
    trustStore.setEntry("ca", new KeyStore.TrustedCertificateEntry(caCert), null)

    val keyStorePasswd = "p@ssw0rd".toCharArray
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(null, null)
    keyStore.setCertificateEntry("client", clientCert)
    keyStore.setKeyEntry("key", clientKey, keyStorePasswd, Array(clientCert))

    SSLContexts.custom()
      .loadTrustMaterial(trustStore, null)
      .loadKeyMaterial(keyStore, keyStorePasswd)
      .build()
  }

}
