package com.postflight.spring;

import com.postflight.core.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Production-framework route adapter; API rules live in dependency-free core. */
@RestController
@RequestMapping("/api")
public final class PostFlightApiController {
    private final ApiDispatcher dispatcher;
    public PostFlightApiController(ApiDispatcher dispatcher){this.dispatcher=dispatcher;}
    @RequestMapping(path={"","/**"},method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE},produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> invoke(HttpServletRequest req,@RequestBody(required=false)String body){
        ApiDispatcher.Answer answer=dispatcher.handle(req.getMethod(),req.getRequestURI(),body);
        return ResponseEntity.status(answer.status()).cacheControl(CacheControl.noStore()).body(answer.json());
    }
}
