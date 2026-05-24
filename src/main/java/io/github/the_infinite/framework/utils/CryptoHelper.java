package io.github.the_infinite.framework.utils;

import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

@SuppressWarnings("unused")
public final class CryptoHelper {
  private static final String DEFAULT_ALGORITHM = "AES/GCM/NoPadding";

  private CryptoHelper() {
  }

  public static SecretKey asSecretKey(String secret, String salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
    final var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    final var spec = new PBEKeySpec(secret.toCharArray(), salt.getBytes(), 65536, 256);
    return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
  }

  public static SecretKey generateAesKey(int n) throws NoSuchAlgorithmException {
    final var keyGenerator = KeyGenerator.getInstance("AES");
    keyGenerator.init(n);
    return keyGenerator.generateKey();
  }

  public static SecretKey generateAesKey() throws NoSuchAlgorithmException {
    return generateAesKey(256);
  }

  public static SecretKey asSecretKey(String secret) throws NoSuchAlgorithmException, InvalidKeySpecException {
    final var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    final var spec = new PBEKeySpec(secret.toCharArray());
    return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
  }

  public static GCMParameterSpec generateIv() {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    return getIvOf(iv);
  }

  public static GCMParameterSpec getIvOf(byte[] bytes) {
    return new GCMParameterSpec(128, bytes);
  }

  public static String encryptAes(String algorithm, String input, SecretKey key, GCMParameterSpec iv) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
    final var cipher = Cipher.getInstance(algorithm);
    cipher.init(Cipher.ENCRYPT_MODE, key, iv);
    final var cipherText = cipher.doFinal(input.getBytes());
    return DataHelpers.toBase64(new String(cipherText));
  }

  public static String encryptAes(String data, SecretKey key, GCMParameterSpec iv) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
    return encryptAes(DEFAULT_ALGORITHM, data, key, iv);
  }

  public static String encryptAes(String data, SecretKey key) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
    return encryptAes(data, key, generateIv());
  }

  public static String encryptAes(String data, String secret) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException, InvalidKeySpecException {
    return encryptAes(data, asSecretKey(secret));
  }

  public static String encryptAes(String data, String secret, String salt) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException, InvalidKeySpecException {
    return encryptAes(data, asSecretKey(secret, salt));
  }

  public static String encryptAes(String data, String secret, GCMParameterSpec iv) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException, InvalidKeySpecException {
    return encryptAes(data, asSecretKey(secret), iv);
  }

  public static String decryptAes(String algorithm, String cipherText, SecretKey key, GCMParameterSpec iv) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
    Cipher cipher = Cipher.getInstance(algorithm);
    cipher.init(Cipher.DECRYPT_MODE, key, iv);
    byte[] plainText = cipher.doFinal(DataHelpers.fromBase64(cipherText).getBytes());
    return new String(plainText);
  }


  public static String decryptAes(String cipherText, SecretKey key, GCMParameterSpec iv) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
    return decryptAes(DEFAULT_ALGORITHM, cipherText, key, iv);
  }

  public static KeyPair generateRSAKeys() throws Exception {
    final var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048, new SecureRandom());
    return keyPairGenerator.generateKeyPair();
  }

  public static KeyPair parseRSAKeys(byte[] publicKeyBytes, byte[] privateKeyBytes) throws Exception {
    final var keyFactory = KeyFactory.getInstance("RSA");
    final var publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
    final var privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    return new KeyPair(publicKey, privateKey);
  }

  public static byte[] encrypt(String data, PublicKey key) throws Exception {
    Cipher cipher = Cipher.getInstance("RSA");
    cipher.init(Cipher.ENCRYPT_MODE, key);
    return cipher.doFinal(data.getBytes());
  }

  public static byte[] decrypt(String data, PrivateKey key) throws Exception {
    Cipher cipher = Cipher.getInstance("RSA");
    cipher.init(Cipher.DECRYPT_MODE, key);
    return cipher.doFinal(data.getBytes());
  }
}
