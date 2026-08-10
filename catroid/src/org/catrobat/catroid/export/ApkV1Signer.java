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

import android.util.Base64;
import android.util.Log;

import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Minimal, dependency-light implementation of Android's APK Signature
 * Scheme v1 (JAR signing). This is sufficient for a sideloaded / "install
 * from unknown sources" APK on all Android versions - devices running
 * Android 7+ additionally prefer v2/v3 signatures for faster verification,
 * but fall back to accepting v1-only APKs.
 */
final class ApkV1Signer {

	private static final String TAG = ApkV1Signer.class.getSimpleName();
	private static final String DIGEST_ALGORITHM = "SHA1";
	private static final String SIGNER_NAME = "CERT";

	private ApkV1Signer() {
	}

	static boolean signApk(File inputApk, File outputApk, ApkSigningKeyProvider.SigningIdentity identity) {
		try {
			if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
				Security.addProvider(new BouncyCastleProvider());
			}

			X509Certificate certificate = loadCertificate(identity.certificateBytes);
			PrivateKey privateKey = loadPrivateKey(identity.privateKeyBytes);

			BuiltManifest builtManifest = buildManifestInternal(inputApk);
			byte[] signatureFileBytes = buildSignatureFileInternal(builtManifest);

			byte[] signatureBlock = buildPkcs7SignatureBlock(signatureFileBytes, certificate, privateKey);

			writeSignedApk(inputApk, outputApk, builtManifest.fullManifestBytes, signatureFileBytes, signatureBlock);

			return true;
		} catch (Exception exception) {
			Log.e(TAG, "APK signing failed", exception);
			return false;
		}
	}

	private static X509Certificate loadCertificate(byte[] certificateBytes) throws Exception {
		CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		return (X509Certificate) certificateFactory.generateCertificate(
				new ByteArrayInputStream(certificateBytes));
	}

	private static PrivateKey loadPrivateKey(byte[] privateKeyBytes) throws Exception {
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		return keyFactory.generatePrivate(keySpec);
	}

	private static final String LINE_BREAK = "\r\n";

	/** Holds the raw per-entry section text alongside the parsed Manifest so
	 *  the .SF digest step can hash the exact same bytes written to MANIFEST.MF. */
	private static final class BuiltManifest {
		final Manifest manifest;
		final byte[] fullManifestBytes;
		final java.util.LinkedHashMap<String, byte[]> entrySectionBytes = new java.util.LinkedHashMap<>();

		BuiltManifest(Manifest manifest, byte[] fullManifestBytes) {
			this.manifest = manifest;
			this.fullManifestBytes = fullManifestBytes;
		}
	}

	/** Builds META-INF/MANIFEST.MF: one section per APK entry with its SHA1 digest. */
	private static BuiltManifest buildManifestInternal(File inputApk) throws Exception {
		Manifest manifest = new Manifest();
		Attributes mainAttributes = manifest.getMainAttributes();
		mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		mainAttributes.putValue("Created-By", "KorexCode APK Export");

		StringBuilder fullText = new StringBuilder();
		fullText.append("Manifest-Version: 1.0").append(LINE_BREAK);
		fullText.append("Created-By: KorexCode APK Export").append(LINE_BREAK);
		fullText.append(LINE_BREAK);

		java.util.LinkedHashMap<String, byte[]> sections = new java.util.LinkedHashMap<>();

		JarFile jarFile = new JarFile(inputApk);
		try {
			List<String> names = new ArrayList<>();
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
					continue;
				}
				names.add(entry.getName());
			}
			java.util.Collections.sort(names);

			for (String name : names) {
				JarEntry entry = jarFile.getJarEntry(name);
				byte[] digest = digestEntry(jarFile, entry);
				String digestB64 = Base64.encodeToString(digest, Base64.NO_WRAP);

				String sectionText = "Name: " + name + LINE_BREAK
						+ DIGEST_ALGORITHM + "-Digest: " + digestB64 + LINE_BREAK
						+ LINE_BREAK;
				byte[] sectionBytes = sectionText.getBytes("UTF-8");
				sections.put(name, sectionBytes);
				fullText.append(sectionText);

				Attributes entryAttributes = new Attributes();
				entryAttributes.putValue(DIGEST_ALGORITHM + "-Digest", digestB64);
				manifest.getEntries().put(name, entryAttributes);
			}
		} finally {
			jarFile.close();
		}

		BuiltManifest result = new BuiltManifest(manifest, fullText.toString().getBytes("UTF-8"));
		result.entrySectionBytes.putAll(sections);
		return result;
	}

	private static byte[] digestEntry(JarFile jarFile, JarEntry entry) throws IOException {
		MessageDigest messageDigest;
		try {
			messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
		} catch (Exception exception) {
			throw new IOException(exception);
		}
		InputStream inputStream = jarFile.getInputStream(entry);
		byte[] buffer = new byte[8192];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			messageDigest.update(buffer, 0, read);
		}
		inputStream.close();
		return messageDigest.digest();
	}

	/**
	 * Builds META-INF/CERT.SF: digest of the whole manifest plus, per entry,
	 * the digest of that entry's exact manifest section bytes (mirrors what
	 * jarsigner produces so standard Android verification logic accepts it).
	 */
	private static byte[] buildSignatureFileInternal(BuiltManifest builtManifest) throws Exception {
		MessageDigest wholeManifestDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
		String wholeDigestB64 = Base64.encodeToString(
				wholeManifestDigest.digest(builtManifest.fullManifestBytes), Base64.NO_WRAP);

		StringBuilder sfText = new StringBuilder();
		sfText.append("Signature-Version: 1.0").append(LINE_BREAK);
		sfText.append(DIGEST_ALGORITHM).append("-Digest-Manifest: ").append(wholeDigestB64).append(LINE_BREAK);
		sfText.append("Created-By: KorexCode APK Export").append(LINE_BREAK);
		sfText.append(LINE_BREAK);

		for (java.util.Map.Entry<String, byte[]> entry : builtManifest.entrySectionBytes.entrySet()) {
			MessageDigest sectionDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
			String sectionDigestB64 = Base64.encodeToString(
					sectionDigest.digest(entry.getValue()), Base64.NO_WRAP);
			sfText.append("Name: ").append(entry.getKey()).append(LINE_BREAK);
			sfText.append(DIGEST_ALGORITHM).append("-Digest: ").append(sectionDigestB64).append(LINE_BREAK);
			sfText.append(LINE_BREAK);
		}

		return sfText.toString().getBytes("UTF-8");
	}

	private static byte[] buildPkcs7SignatureBlock(byte[] signatureFileBytes, X509Certificate certificate,
			PrivateKey privateKey) throws Exception {
		List<X509Certificate> certList = new ArrayList<>();
		certList.add(certificate);

		ContentSigner contentSigner = new JcaContentSignerBuilder("SHA1withRSA")
				.setProvider(BouncyCastleProvider.PROVIDER_NAME)
				.build(privateKey);

		CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
		generator.addSignerInfoGenerator(
				new JcaSignerInfoGeneratorBuilder(
						new JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build())
						.build(contentSigner, certificate));
		generator.addCertificates(new JcaCertStore(certList));

		CMSSignedData signedData = generator.generate(new CMSProcessableByteArray(signatureFileBytes), false);
		return signedData.getEncoded();
	}

	private static void writeSignedApk(File inputApk, File outputApk, byte[] manifestBytes,
			byte[] signatureFileBytes, byte[] signatureBlock) throws IOException {
		java.util.zip.ZipInputStream zipInputStream =
				new java.util.zip.ZipInputStream(new FileInputStream(inputApk));
		java.util.zip.ZipOutputStream zipOutputStream =
				new java.util.zip.ZipOutputStream(new FileOutputStream(outputApk));

		java.util.zip.ZipEntry entry;
		byte[] buffer = new byte[8192];
		while ((entry = zipInputStream.getNextEntry()) != null) {
			zipOutputStream.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
			int read;
			while ((read = zipInputStream.read(buffer)) != -1) {
				zipOutputStream.write(buffer, 0, read);
			}
			zipOutputStream.closeEntry();
		}
		zipInputStream.close();

		zipOutputStream.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
		zipOutputStream.write(manifestBytes);
		zipOutputStream.closeEntry();

		zipOutputStream.putNextEntry(new java.util.zip.ZipEntry("META-INF/" + SIGNER_NAME + ".SF"));
		zipOutputStream.write(signatureFileBytes);
		zipOutputStream.closeEntry();

		zipOutputStream.putNextEntry(new java.util.zip.ZipEntry("META-INF/" + SIGNER_NAME + ".RSA"));
		zipOutputStream.write(signatureBlock);
		zipOutputStream.closeEntry();

		zipOutputStream.close();
	}
}
