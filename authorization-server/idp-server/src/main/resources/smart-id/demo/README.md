# Smart-ID DEMO trust anchors

`smart-id-demo-truststore.p12` holds the CA certificates that Smart-ID **demo**
authentication certificates chain to. `SmartIdCertificateValidator` loads it at
startup and validates every certificate SK returns against it — explicitly,
instead of against the JVM default truststore.

The `ca/` directory holds the same certificates as PEM. They are not read at
runtime; they are here so this directory can be reviewed in a diff rather than
taken on trust as a binary blob, and so the store can be rebuilt from scratch.

## Why not the JVM default truststore

The default truststore trusts every public CA on the internet. Against it,
"this certificate validates" means "somebody, somewhere, was willing to issue
it" — which for an identity assertion is no check at all.

Anchoring on SK's own issuing CAs is narrower than anchoring on their roots
alone: a certificate issued by some other CA under the same root does not
validate. That is the opposite of the usual browser posture and correct here.
We are not trying to accept the web; we are trying to accept Smart-ID.

## DEMO only — and the production root is deliberately absent

**The demo and production chains are disjoint.** This store rejects every
production certificate, and a production store would reject every demo one. That
is why the truststore, the API base URL and `scheme-name` are one
environment-scoped set in `application.yml` and have to move together.

SK's own demo application bundles the *production* `EE Certification Centre Root
CA` alongside the test roots. It is **excluded here on purpose**: including it
would mean production certificates validate against a configuration that is
demo in every other respect, which is precisely the confusion the scoping
exists to prevent.

## What is in it

Eight certificates — three test roots and five test issuing CAs.

| Alias | Subject CN | Issued by |
|---|---|---|
| `test-of-ee-cert-centre-root-ca` | TEST of EE Certification Centre Root CA | *self-signed root* |
| `test-of-sk-root-g1e` | TEST of SK ID Solutions ROOT G1E | *self-signed root* |
| `test-of-sk-root-g1r` | TEST of SK ID Solutions ROOT G1R | *self-signed root* |
| `test-of-eid-sk-2016` | TEST of EID-SK 2016 | TEST of EE Certification Centre Root CA |
| `test-of-nq-sk-2016` | TEST of NQ-SK 2016 | TEST of EE Certification Centre Root CA |
| `test-of-eid-q-2021e` | TEST of SK ID Solutions EID-Q 2021E | TEST of SK ID Solutions ROOT G1E |
| `test-of-eid-q-2024e` | TEST of SK ID Solutions EID-Q 2024E | TEST of SK ID Solutions ROOT G1E |
| `test-of-eid-q-2024r` | TEST of SK ID Solutions EID-Q 2024R | TEST of SK ID Solutions ROOT G1R |

Both roots *and* issuing CAs are present, and the validator uses a
`CertPathBuilder` rather than validating a fixed path. SK returns the end-entity
certificate alone, with no chain, so any intermediate needed to reach a root has
to come from this store. With the issuing CAs present a path usually resolves in
one hop; keeping the roots means a longer path resolves too, so adding a new SK
intermediate later needs no code change.

## Where they came from

Issuing CAs, from SK's published certificate list
(<https://www.skidsolutions.eu/resources/certificates/>):

```
https://www.skidsolutions.eu/upload/files/TEST%20of%20EID-SK%202016_reissued.pem
https://www.skidsolutions.eu/upload/files/TEST%20of%20NQ-SK%202016_reissued.pem
https://www.skidsolutions.eu/upload/files/TEST_EID-Q_2021E.pem.crt
https://c.sk.ee/TEST_of_SK_ID_Solutions_EID-Q_2024E.pem.crt
https://c.sk.ee/TEST_of_SK_ID_Solutions_EID-Q_2024R.pem.crt
```

Test roots, exported from the trust anchor store shipped in SK's own demo
application, `SK-EID/smart-id-java-demo`:

```
src/main/resources/sid_trust_anchor_certificates.jks   (store password: changeit)
```

## Rebuilding

The store password is `changeit`. **It is not a secret** — PKCS#12 demands one
even when every entry is a public certificate. Do not treat it as one, and do
not move it to an environment variable as though it were.

```bash
keytool -importcert -noprompt -storetype PKCS12 \
  -keystore smart-id-demo-truststore.p12 -storepass changeit \
  -alias test-of-eid-q-2024e -file ca/test-of-eid-q-2024e.pem
```

Repeat per certificate in `ca/`. To inspect what is currently trusted:

```bash
keytool -list -storetype PKCS12 -keystore smart-id-demo-truststore.p12 -storepass changeit
```

## When a login fails with "does not chain to a trusted CA"

SK rotates issuing CAs, and a new one will not be in this store. Fetch it from
the certificate list above, import it with the command above, and add a row to
the table. The failure is loud and specific precisely so that this is the
obvious next step rather than a debugging session.
