package com.postflight.spring;

import com.postflight.core.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;

@SpringBootApplication(scanBasePackages = "com.postflight.spring")
public class PostFlightApplication {
    public static void main(String[] args){SpringApplication.run(PostFlightApplication.class,args);}
    @Bean PostFlightService postFlightService(@Value("${postflight.data-directory:./data}") String directory,@Value("${postflight.seed-file:./docs/seed.json}") String seed)throws Exception{return new PostFlightService(Path.of(directory),Path.of(seed));}
    @Bean ApiDispatcher apiDispatcher(PostFlightService service){return new ApiDispatcher(service);}
}
