package com.abclogistics.pas.identity.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(JwtIssuerProperties.class)
public class JwtKeyConfig {

    @Bean
    RSAPrivateKey jwtSigningKey(JwtIssuerProperties props) {
        try {
            String pem = Files.readString(Path.of(props.privateKeyPath()))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load JWT signing key from " + props.privateKeyPath(), e);
        }
    }

    @Bean
    JwtIssuer jwtIssuer(RSAPrivateKey jwtSigningKey, JwtIssuerProperties props) {
        return new JwtIssuer(jwtSigningKey, props.issuer(), props.accessTokenTtl());
    }
}
