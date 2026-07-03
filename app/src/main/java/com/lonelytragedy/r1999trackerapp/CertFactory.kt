package com.lonelytragedy.r1999trackerapp

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.x500.X500Name
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object CertFactory {

    @Volatile
    private var cached: SSLContext? = null

    fun serverContext(): SSLContext {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = build()
            cached = ctx
            return ctx
        }
    }

    private fun build(): SSLContext {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000)
        val dn = X500Name("CN=R1999 Grabber, O=R1999 Grabber")
        val serial = BigInteger.valueOf(now)

        val builder = JcaX509v3CertificateBuilder(dn, serial, notBefore, notAfter, dn, kp.public)
        val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider("BC").build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("key", kp.private, CharArray(0), arrayOf<Certificate>(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }
}
