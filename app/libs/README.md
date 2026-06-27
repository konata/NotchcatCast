`java-airplay-lib-v1.0.7-local.jar` is built from `serezhka/java-airplay`
tag `v1.0.7`, module `lib`.

Local patches kept intentionally small:

- expose `Pairing(byte[] seed)` for stable AirPlay identity
- expose `Pairing.publicKey()` for the advertised TXT `pk`
- keep `FairPlayVideoDecryptor` working when `sharedSecret == null`
- remove SLF4J/Lombok logging references from the jar
