package com.postflight.local;

import com.postflight.core.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Localhost-only runtime with the same application API as the Spring Boot controller. */
public final class LocalServer implements AutoCloseable{
 private final HttpServer server;
 private final ApiDispatcher api;
 private final WorkspaceGateway workspaceGateway;
 private final Path web;
 private final String privateProxyOrigin;
 public LocalServer(int port,Path root)throws Exception{
  Path data=root.resolve("data"),seed=root.resolve("docs/seed.json");web=root.resolve("web").toAbsolutePath().normalize();
  PostFlightService service=new PostFlightService(data,seed);api=new ApiDispatcher(service);
  workspaceGateway=new WorkspaceGateway(root);
  boolean publicDemo="true".equalsIgnoreCase(System.getenv("POSTFLIGHT_PUBLIC_DEMO"));
  boolean privateProxy="true".equalsIgnoreCase(System.getenv("POSTFLIGHT_PRIVATE_PROXY"));
  privateProxyOrigin=verifiedProxyOrigin(privateProxy&&!publicDemo,
      ClerkSessionVerifier.configured(),System.getenv("POSTFLIGHT_APP_ORIGIN"));
  String bindHost = publicDemo || privateProxy ? "0.0.0.0" : "127.0.0.1";
  server=HttpServer.create(new InetSocketAddress(bindHost,port),0);
  server.createContext("/",this::handle);server.setExecutor(Executors.newFixedThreadPool(8));
 }
 /**
  * Explicit TLS reverse-proxy origin. The secure runtime NEVER binds to an
  * external interface unless both Clerk and the expected HTTPS origin exist.
  * Supplying a Host or X-Forwarded-Proto request header cannot enable this.
  */
 static String verifiedProxyOrigin(boolean privateProxy,boolean clerkConfigured,String raw) {
  if(!privateProxy)return "";
  if(!clerkConfigured)throw new IllegalArgumentException("Private proxy requires configured Clerk");
  try {
   URI uri=URI.create(Objects.requireNonNull(raw,"POSTFLIGHT_APP_ORIGIN required"));
   if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getHost().isBlank()
      ||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
      ||!(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/"))
      ||uri.getPort()!=-1)
     throw new IllegalArgumentException("Private proxy origin must be an HTTPS hostname without a port or path");
   return "https://"+uri.getHost().toLowerCase(Locale.ROOT);
  } catch(RuntimeException e) {
   throw new IllegalArgumentException("Invalid POSTFLIGHT_APP_ORIGIN for private TLS proxy",e);
  }
 }
 public void start(){server.start();}
 public int port(){return server.getAddress().getPort();}
 @Override public void close(){server.stop(0);}
 private void handle(HttpExchange ex)throws IOException{
  try{
   Headers h=ex.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("Referrer-Policy","no-referrer");h.set("X-Frame-Options","DENY");
   String path=ex.getRequestURI().getPath(),method=ex.getRequestMethod().toUpperCase(Locale.ROOT);
   if(path.startsWith("/api/")){
    h.set("Content-Type","application/json; charset=utf-8");h.set("Cache-Control","no-store");
    boolean mutation=List.of("POST","PUT","PATCH","DELETE").contains(method);
    if(mutation&&!"application/json".equalsIgnoreCase(Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";",2)[0].trim())){send(ex,415,Json.stringify(Map.of("error","Content-Type must be application/json")));return;}
    String origin=ex.getRequestHeaders().getFirst("Origin");
    boolean statelessPreview=path.equals("/api/demo/preflight")&&"POST".equals(method);
    if(mutation&&origin!=null){
      boolean validOrigin=origin.equals("http://127.0.0.1:"+port())||origin.equals("http://localhost:"+port())
          ||(!privateProxyOrigin.isEmpty()&&origin.equals(privateProxyOrigin));
      if(statelessPreview){
        try{
          URI source=URI.create(origin);
          String host=ex.getRequestHeaders().getFirst("Host");
          validOrigin=host!=null&&source.getRawAuthority()!=null&&host.equalsIgnoreCase(source.getRawAuthority())
              &&List.of("http","https").contains(source.getScheme());
        }catch(IllegalArgumentException invalid){validOrigin=false;}
      }
      if(!validOrigin){send(ex,403,Json.stringify(Map.of("error","Cross-origin requests are disabled")));return;}
    }
    if(ex.getRequestHeaders().getFirst("Content-Length")!=null){try{if(Long.parseLong(ex.getRequestHeaders().getFirst("Content-Length"))>1024*1024){send(ex,413,"{\"error\":\"Request too large\"}");return;}}catch(NumberFormatException e){send(ex,400,"{\"error\":\"Invalid Content-Length\"}");return;}}
    byte[] request=ex.getRequestBody().readNBytes(1024*1024+1);
    if(request.length>1024*1024){send(ex,413,"{\"error\":\"Request too large\"}");return;}
    // Authenticated mode must not expose any unscoped integration routes.
    if(ClerkSessionVerifier.authRequested()&&!path.startsWith("/api/secure/")
        &&!path.equals("/api/health")&&!path.equals("/api/demo/preflight")){
      send(ex,403,Json.stringify(Map.of("error","Legacy demo API disabled in authenticated mode; use /api/secure")));
      return;
    }
    // Fixed GET-only Zernio proxy using the visitor's API key for this request.
    // No credentials are saved, and there is no publishing or account-write operation.
    if(path.equals("/api/zernio/accounts")){
      if(!"GET".equals(method)){send(ex,405,Json.stringify(Map.of("error","Read-only endpoint")));return;}
      try{
        Map<String,Object> info=ZernioClient.inspectAccounts(ex.getRequestHeaders().getFirst("Authorization"));
        send(ex,200,Json.stringify(info));
      }catch(ZernioClient.Failure e){send(ex,e.status,Json.stringify(Map.of("error",e.getMessage())));}
      return;
    }
    if(path.equals("/api/zernio/live")){
      if(!"GET".equals(method)){send(ex,405,Json.stringify(Map.of("error","Read-only endpoint")));return;}
      try{
        Map<String,Object> info=ZernioClient.inspectLive(ex.getRequestHeaders().getFirst("Authorization"));
        send(ex,200,Json.stringify(info));
      }catch(ZernioClient.Failure e){send(ex,e.status,Json.stringify(Map.of("error",e.getMessage())));}
      return;
    }
    // When Clerk is enabled, never expose the old shared, unauthenticated
    // demo database as tenant data or allow legacy unscoped mutations.
    ApiDispatcher.Answer result=path.startsWith("/api/secure/")
        ?workspaceGateway.handle(method,path,new String(request,StandardCharsets.UTF_8),ex.getRequestHeaders().getFirst("Authorization"))
        :api.handle(method,path,new String(request,StandardCharsets.UTF_8));
    send(ex,result.status(),result.json());return;
   }
   if(!"GET".equals(method)&&!"HEAD".equals(method)){send(ex,405,"Method not allowed");return;}
   Path file=(path.equals("/")?web.resolve("index.html"):web.resolve(path.substring(1))).normalize();
   if(!file.startsWith(web)||!Files.isRegularFile(file)){send(ex,404,"Not found");return;}
   h.set("Content-Type",path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":path.endsWith(".png")?"image/png":path.endsWith(".svg")?"image/svg+xml":"text/html; charset=utf-8");
   String clerkOrigin=ClerkSessionVerifier.configured()?System.getenv("POSTFLIGHT_CLERK_ISSUER"):"";
   String remote=clerkOrigin.isEmpty()?"":" "+clerkOrigin;
   String csp="default-src 'self'; "
       +"script-src 'self'"+remote+" https://challenges.cloudflare.com https://*.protect.clerk.com; "
       +"style-src 'self' 'unsafe-inline'; "
       +"img-src 'self' data: https://img.clerk.com; "
       +"connect-src 'self'"+remote+" https://*.protect.clerk.com:* https://clerk-telemetry.com https://*.clerk-telemetry.com; "
       +"frame-src 'self' https://challenges.cloudflare.com https://*.protect.clerk.com; "
       +"worker-src 'self' blob:; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
   h.set("Content-Security-Policy",csp);
   if(path.equals("/")||path.endsWith(".html")||path.endsWith(".js")||path.endsWith(".css")) h.set("Cache-Control","no-cache, must-revalidate");
   byte[] bytes=Files.readAllBytes(file);ex.sendResponseHeaders(200,method.equals("HEAD")?-1:bytes.length);if(!method.equals("HEAD")){try(OutputStream out=ex.getResponseBody()){out.write(bytes);}}
  }catch(Exception e){try{send(ex,500,"Internal error");}catch(Exception ignored){}}finally{ex.close();}
 }
 private void send(HttpExchange ex,int status,String data)throws IOException{byte[] bytes=data.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(status,bytes.length);try(OutputStream out=ex.getResponseBody()){out.write(bytes);}}
}
