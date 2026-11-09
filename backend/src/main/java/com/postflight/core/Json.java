package com.postflight.core;

import java.util.*;

/** Minimal, dependency-free JSON codec used by the Java 17 local runtime and Spring adapter. */
public final class Json {
    private Json() {}
    public static Object parse(String text) { Parser p = new Parser(text); Object value=p.value(); p.ws(); if(p.i!=text.length()) throw new IllegalArgumentException("Unexpected JSON trailing data"); return value; }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value){ if(!(value instanceof Map<?,?> raw)) throw new IllegalArgumentException("JSON object required"); return (Map<String,Object>)raw; }
    @SuppressWarnings("unchecked") public static List<Object> array(Object value){ if(!(value instanceof List<?> list)) throw new IllegalArgumentException("JSON array required");return (List<Object>)list; }
    public static String stringify(Object value){StringBuilder out=new StringBuilder();write(value,out);return out.toString();}
    private static void write(Object v,StringBuilder b){
      if(v==null){b.append("null");return;}if(v instanceof String s){b.append('"');for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}b.append('"');return;}
      if(v instanceof Number || v instanceof Boolean){b.append(v);return;}
      if(v instanceof Map<?,?> m){b.append('{');boolean first=true;for(var e:m.entrySet()){if(!first)b.append(',');first=false;write(e.getKey().toString(),b);b.append(':');write(e.getValue(),b);}b.append('}');return;}
      if(v instanceof Collection<?> c){b.append('[');boolean first=true;for(Object x:c){if(!first)b.append(',');first=false;write(x,b);}b.append(']');return;}
      throw new IllegalArgumentException("Unsupported JSON type: "+v.getClass());
    }
    private static final class Parser{
      final String s;int i;Parser(String text){s=Objects.requireNonNull(text);}
      void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
      Object value(){ws();if(i>=s.length())error("Expected JSON value");char c=s.charAt(i);if(c=='{')return object();if(c=='[')return array();if(c=='"')return string();if(c=='t')return literal("true",true);if(c=='f')return literal("false",false);if(c=='n')return literal("null",null);if(c=='-'||Character.isDigit(c))return number();error("Unexpected character");return null;}
      Object literal(String token,Object result){if(!s.startsWith(token,i))error("Invalid literal");i+=token.length();return result;}
      Map<String,Object> object(){i++;Map<String,Object> out=new LinkedHashMap<>();ws();if(peek('}')){i++;return out;}while(true){ws();if(!peek('"'))error("Object key expected");String key=string();ws();expect(':');out.put(key,value());ws();if(peek('}')){i++;return out;}expect(',');}}
      List<Object> array(){i++;List<Object> out=new ArrayList<>();ws();if(peek(']')){i++;return out;}while(true){out.add(value());ws();if(peek(']')){i++;return out;}expect(',');}}
      String string(){expect('"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c=='\\'){if(i>=s.length())error("Incomplete escape");char e=s.charAt(i++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())error("Bad unicode escape");try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException ex){error("Bad unicode escape");}i+=4;}default->error("Bad escape");}}else{if(c<32)error("Unescaped control character");b.append(c);}}error("Unclosed string");return "";}
      Number number(){int start=i;if(peek('-'))i++;if(i>=s.length())error("Bad number");if(peek('0'))i++;else{if(!Character.isDigit(s.charAt(i)))error("Bad number");while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}if(peek('.')){i++;if(i>=s.length()||!Character.isDigit(s.charAt(i)))error("Bad fraction");while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}if(peek('e')||peek('E')){i++;if(peek('+')||peek('-'))i++;if(i>=s.length()||!Character.isDigit(s.charAt(i)))error("Bad exponent");while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}String n=s.substring(start,i);try{if(n.contains(".")||n.contains("e")||n.contains("E"))return Double.parseDouble(n);return Long.parseLong(n);}catch(NumberFormatException e){error("Invalid numeric value");return 0;}}
      void expect(char c){ws();if(!peek(c))error("Expected '"+c+"'");i++;}
      boolean peek(char c){return i<s.length()&&s.charAt(i)==c;}
      void error(String message){throw new IllegalArgumentException(message+" at position "+i);}
    }
}
