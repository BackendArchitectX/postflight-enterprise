package com.postflight.local;
import java.nio.file.*;
public final class Main{
 public static void main(String[] args)throws Exception{
  int port=args.length>0?Integer.parseInt(args[0]):Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
  Path root=Path.of(System.getProperty("postflight.root",".")).toAbsolutePath().normalize();
  LocalServer app=new LocalServer(port,root);app.start();Runtime.getRuntime().addShutdownHook(new Thread(app::close));
  System.out.println("PostFlight Enterprise Local API running at http://127.0.0.1:"+app.port()+"/");
  System.out.println("Java 17 compatible | persistent data in "+root.resolve("data/postflight-data.json"));
  System.out.println("WARNING: "+com.postflight.core.PostFlightService.DEV_WARNING);
  Thread.currentThread().join();
 }
}
