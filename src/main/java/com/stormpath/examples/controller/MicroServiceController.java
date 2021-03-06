package com.stormpath.examples.controller;

import com.stormpath.examples.exception.UnauthorizedException;
import com.stormpath.examples.model.AccountsResponseBuilder;
import com.stormpath.examples.model.AccountsResponse;
import com.stormpath.examples.service.AdminService;
import com.stormpath.examples.service.CommunicationService;
import com.stormpath.sdk.account.Account;
import com.stormpath.sdk.application.Application;
import com.stormpath.sdk.client.Client;
import com.stormpath.sdk.lang.Strings;
import com.stormpath.sdk.servlet.account.AccountResolver;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.UUID;

@RestController
public class MicroServiceController {
    @Value("#{ @environment['service.remote.uri'] ?: 'http://localhost:8081' }")
    String remoteUri;

    @Autowired
    Application application;

    @Autowired
    Client client;

    @Autowired
    AdminService adminService;

    @Autowired
    CommunicationService communicationService;

    private static final String REMOTE_SERVICE_ENDPOINT = "/accounts_service";

    @RequestMapping("/accounts")
    public @ResponseBody AccountsResponse accounts(HttpServletRequest req) throws Exception {

        if (!isAuthenticated(req)) {
            throw new UnauthorizedException("You must authenticate!");
        }

        Account account = AccountResolver.INSTANCE.getAccount(req);

        // create a new JWT with all this information
        JwtBuilder jwtBuilder = Jwts.builder()
            .setSubject(account.getHref())
            .setIssuedAt(new Date(System.currentTimeMillis()))
            .setExpiration(new Date(System.currentTimeMillis() + (1000 * 60)))
            .setId(UUID.randomUUID().toString());

        String secret = client.getApiKey().getSecret();
        String token = jwtBuilder.signWith(
            SignatureAlgorithm.HS512, secret.getBytes("UTF-8")
        ).compact();

        return communicationService.doRemoteRequest(remoteUri + REMOTE_SERVICE_ENDPOINT, token);
    }

    @RequestMapping(REMOTE_SERVICE_ENDPOINT)
    public @ResponseBody AccountsResponse microservice(@RequestParam String token) throws Exception {

        if (!Strings.hasText(token)) {
            throw new UnauthorizedException("Missing or Empty token!");
        }

        // verify jwt
        String secret = client.getApiKey().getSecret();
        Jws<Claims> claims =
            Jwts.parser().setSigningKey(secret.getBytes("UTF-8")).parseClaimsJws(token);

        String accountHref = claims.getBody().getSubject();

        Account account = client.getResource(accountHref, Account.class);

        return adminService.buildAccountsResponse(account);
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public @ResponseBody AccountsResponse unauthorized(UnauthorizedException ex) {
        return AccountsResponseBuilder.newInstance()
            .status(AccountsResponse.STATUS.ERROR)
            .message(ex.getMessage())
            .build();
    }

    private boolean isAuthenticated(HttpServletRequest req) {
        return AccountResolver.INSTANCE.getAccount(req) != null;
    }
}
