/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2016 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.export;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Generates and caches a self-signed key/certificate pair used to sign
 * APKs exported from within the app (see {@link ApkExporter}).
 *
 * This is intentionally NOT a Play Store style release key: it exists
 * purely so that an exported APK can be installed on a device via
 * "install from unknown sources". Every installation of this app on a
 * given device will keep re-using the same generated key so that
 * repeated exports of the same project can be re-installed as updates.
 */
public final class ApkSigningKeyProvider {

	private static final String TAG = ApkSigningKeyProvider.class.getSimpleName();
	private static final String KEY_FILE_NAME = "export_signing_key.dat";
	private static final String CERT_CN = "CN=KorexCode Export, O=KorexCode, C=XX";
	private static final int KEY_SIZE = 2048;
	private static final long VALIDITY_YEARS = 30L;

	private ApkSigningKeyProvider() {
	}

	public static class SigningIdentity implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		public final byte[] certificateBytes;
		public final byte[] privateKeyBytes;

		SigningIdentity(byte[] certificateBytes, byte[] privateKeyBytes) {
			this.certificateBytes = certificateBytes;
			this.privateKeyBytes = privateKeyBytes;
		}
	}

	public static synchronized SigningIdentity getOrCreateIdentity(Context context) {
		File keyFile = new File(context.getFilesDir(), KEY_FILE_NAME);
		if (keyFile.exists()) {
			SigningIdentity loaded = loadIdentity(keyFile);
			if (loaded != null) {
				return loaded;
			}
		}
		SigningIdentity created = createIdentity();
		if (created != null) {
			saveIdentity(keyFile, created);
		}
		return created;
	}

	private static SigningIdentity createIdentity() {
		try {
			KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
			keyPairGenerator.initialize(KEY_SIZE, new SecureRandom());
			KeyPair keyPair = keyPairGenerator.generateKeyPair();

			X509Certificate certificate = generateSelfSignedCertificate(keyPair);

			return new SigningIdentity(certificate.getEncoded(), keyPair.getPrivate().getEncoded());
		} catch (Exception exception) {
			Log.e(TAG, "Could not create APK signing identity", exception);
			return null;
		}
	}

	private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}

		long now = System.currentTimeMillis();
		Date notBefore = new Date(now - 24L * 60 * 60 * 1000);
		Date notAfter = new Date(now + VALIDITY_YEARS * 365L * 24 * 60 * 60 * 1000);
		BigInteger serial = BigInteger.valueOf(now);
		X500Name subject = new X500Name(CERT_CN);

		X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
				subject,
				serial,
				notBefore,
				notAfter,
				subject,
				org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));

		ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
				.setProvider(BouncyCastleProvider.PROVIDER_NAME)
				.build(keyPair.getPrivate());

		X509CertificateHolder certificateHolder = certificateBuilder.build(contentSigner);

		return new JcaX509CertificateConverter()
				.setProvider(BouncyCastleProvider.PROVIDER_NAME)
				.getCertificate(certificateHolder);
	}

	private static void saveIdentity(File keyFile, SigningIdentity identity) {
		try {
			FileOutputStream fileOutputStream = new FileOutputStream(keyFile);
			ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
			objectOutputStream.writeObject(identity);
			objectOutputStream.close();
			fileOutputStream.close();
		} catch (IOException ioException) {
			Log.e(TAG, "Could not persist APK signing identity", ioException);
		}
	}

	private static SigningIdentity loadIdentity(File keyFile) {
		try {
			FileInputStream fileInputStream = new FileInputStream(keyFile);
			ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
			SigningIdentity identity = (SigningIdentity) objectInputStream.readObject();
			objectInputStream.close();
			fileInputStream.close();
			return identity;
		} catch (Exception exception) {
			Log.e(TAG, "Could not load APK signing identity, will regenerate", exception);
			return null;
		}
	}
}
