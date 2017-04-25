/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keymaster.KeymasterDefs;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utilities for attesting the device's hardware identifiers.
 *
 * @hide
 */
@SystemApi
@TestApi
public abstract class AttestationUtils {
    private static AtomicInteger sSequenceNumber = new AtomicInteger(0);

    private AttestationUtils() {
    }

    /**
     * Specifies that the device should attest its serial number. For use with
     * {@link #attestDeviceIds}.
     *
     * @see #attestDeviceIds
     */
    public static final int ID_TYPE_SERIAL = 1;

    /**
     * Specifies that the device should attest its IMEIs. For use with {@link #attestDeviceIds}.
     *
     * @see #attestDeviceIds
     */
    public static final int ID_TYPE_IMEI = 2;

    /**
     * Specifies that the device should attest its MEIDs. For use with {@link #attestDeviceIds}.
     *
     * @see #attestDeviceIds
     */
    public static final int ID_TYPE_MEID = 3;

    /**
     * Performs attestation of the device's identifiers. This method returns a certificate chain
     * whose first element contains the requested device identifiers in an extension. The device's
     * manufacturer, model, brand, device and product are always also included in the attestation.
     * If the device supports attestation in secure hardware, the chain will be rooted at a
     * trustworthy CA key. Otherwise, the chain will be rooted at an untrusted certificate. See
     * <a href="https://developer.android.com/training/articles/security-key-attestation.html">
     * Key Attestation</a> for the format of the certificate extension.
     * <p>
     * Attestation will only be successful when all of the following are true:
     * 1) The device has been set up to support device identifier attestation at the factory.
     * 2) The user has not permanently disabled device identifier attestation.
     * 3) You have permission to access the device identifiers you are requesting attestation for.
     * <p>
     * For privacy reasons, you cannot distinguish between (1) and (2). If attestation is
     * unsuccessful, the device may not support it in general or the user may have permanently
     * disabled it.
     *
     * @param context the context to use for retrieving device identifiers.
     * @param idTypes the types of device identifiers to attest.
     * @param attestationChallenge a blob to include in the certificate alongside the device
     * identifiers.
     *
     * @return a certificate chain containing the requested device identifiers in the first element
     *
     * @exception SecurityException if you are not permitted to obtain an attestation of the
     * device's identifiers.
     * @exception DeviceIdAttestationException if the attestation operation fails.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @NonNull public static X509Certificate[] attestDeviceIds(Context context,
            @NonNull int[] idTypes, @NonNull byte[] attestationChallenge) throws
            DeviceIdAttestationException {
        // Check method arguments, retrieve requested device IDs and prepare attestation arguments.
        if (idTypes == null) {
            throw new NullPointerException("Missing id types");
        }
        if (attestationChallenge == null) {
            throw new NullPointerException("Missing attestation challenge");
        }
        final KeymasterArguments attestArgs = new KeymasterArguments();
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE, attestationChallenge);
        final Set<Integer> idTypesSet = new ArraySet<>(idTypes.length);
        for (int idType : idTypes) {
            idTypesSet.add(idType);
        }
        TelephonyManager telephonyService = null;
        if (idTypesSet.contains(ID_TYPE_IMEI) || idTypesSet.contains(ID_TYPE_MEID)) {
            telephonyService = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            if (telephonyService == null) {
                throw new DeviceIdAttestationException("Unable to access telephony service");
            }
        }
        for (final Integer idType : idTypesSet) {
            switch (idType) {
                case ID_TYPE_SERIAL:
                    attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_SERIAL,
                            Build.getSerial().getBytes(StandardCharsets.UTF_8));
                    break;
                case ID_TYPE_IMEI: {
                    final String imei = telephonyService.getImei(0);
                    if (imei == null) {
                        throw new DeviceIdAttestationException("Unable to retrieve IMEI");
                    }
                    attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_IMEI,
                            imei.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case ID_TYPE_MEID: {
                    final String meid = telephonyService.getDeviceId();
                    if (meid == null) {
                        throw new DeviceIdAttestationException("Unable to retrieve MEID");
                    }
                    attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_MEID,
                            meid.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown device ID type " + idType);
            }
        }
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_BRAND,
                Build.BRAND.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_DEVICE,
                Build.DEVICE.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_PRODUCT,
                Build.PRODUCT.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_MANUFACTURER,
                Build.MANUFACTURER.getBytes(StandardCharsets.UTF_8));
        attestArgs.addBytes(KeymasterDefs.KM_TAG_ATTESTATION_ID_MODEL,
                Build.MODEL.getBytes(StandardCharsets.UTF_8));

        final KeyStore keyStore = KeyStore.getInstance();
        final String keyAlias = "android_internal_device_id_attestation-"
                + Process.myPid() + "-" + sSequenceNumber.incrementAndGet();
        // Clear any leftover temporary key.
        if (!keyStore.delete(keyAlias)) {
            throw new DeviceIdAttestationException("Unable to remove temporary key");
        }
        try {
            // Generate a temporary key.
            final KeymasterArguments generateArgs = new KeymasterArguments();
            generateArgs.addEnum(KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_VERIFY);
            generateArgs.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_RSA);
            generateArgs.addEnum(KeymasterDefs.KM_TAG_PADDING, KeymasterDefs.KM_PAD_NONE);
            generateArgs.addEnum(KeymasterDefs.KM_TAG_DIGEST, KeymasterDefs.KM_DIGEST_NONE);
            generateArgs.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
            generateArgs.addUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, 2048);
            generateArgs.addUnsignedLong(KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT,
                    RSAKeyGenParameterSpec.F4);
            int errorCode = keyStore.generateKey(keyAlias, generateArgs, null, 0,
                    new KeyCharacteristics());
            if (errorCode != KeyStore.NO_ERROR) {
                throw new DeviceIdAttestationException("Unable to create temporary key",
                        KeyStore.getKeyStoreException(errorCode));
            }

            // Perform attestation.
            final KeymasterCertificateChain outChain = new KeymasterCertificateChain();
            errorCode = keyStore.attestKey(keyAlias, attestArgs, outChain);
            if (errorCode != KeyStore.NO_ERROR) {
                throw new DeviceIdAttestationException("Unable to perform attestation",
                        KeyStore.getKeyStoreException(errorCode));
            }

            // Extract certificate chain.
            final Collection<byte[]> rawChain = outChain.getCertificates();
            if (rawChain.size() < 2) {
                throw new DeviceIdAttestationException("Attestation certificate chain contained "
                        + rawChain.size() + " entries. At least two are required.");
            }
            final ByteArrayOutputStream concatenatedRawChain = new ByteArrayOutputStream();
            try {
                for (final byte[] cert : rawChain) {
                    concatenatedRawChain.write(cert);
                }
                return CertificateFactory.getInstance("X.509").generateCertificates(
                        new ByteArrayInputStream(concatenatedRawChain.toByteArray()))
                                .toArray(new X509Certificate[0]);
            } catch (Exception e) {
                throw new DeviceIdAttestationException("Unable to construct certificate chain", e);
            }
        } finally {
            // Remove temporary key.
            keyStore.delete(keyAlias);
        }
    }
}
