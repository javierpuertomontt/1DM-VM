package cl.videodetect.app;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;
import android.os.Handler;
import android.os.Looper;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.JavaScriptReplyProxy;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class MediaDiagnostics {
    private final WebView web;
    private final Consumer<String> found;
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private final Set<String> seen=new HashSet<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean frameSupported=false;
    private boolean scriptSupported=false;
    private String js="";
    private int mediaHits=0;
    private int frames=0;
    private int nativeHits=0;
    private int errors=0;
    private static final int MAX_EVENTS=300;

    public MediaDiagnostics(WebView web,Consumer<String> consumer){
        this.web=web;this.found=consumer;
        try{
            InputStream in=web.getContext().getAssets().open("media_monitor.js");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);
            in.close(); js=out.toString("UTF-8");
        }catch(Exception e){event("SISTEMA","","No se pudo cargar el observador");}
    }
    public void install(){
        try{
            // JS can only SEND a bounded string to the app. It cannot invoke
            // downloads, read local files, execute native code or receive secrets.
            if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
                WebViewCompat.addWebMessageListener(web,"VideoDetectMonitor",
                    Collections.singleton("*"),
                    new WebViewCompat.WebMessageListener(){
                        @Override public void onPostMessage(WebView view,WebMessageCompat message,
                           Uri sourceOrigin,boolean isMainFrame,JavaScriptReplyProxy replyProxy){
                           if(message.getType()!=WebMessageCompat.TYPE_STRING)return;
                           String body=message.getData();
                           if(body==null||body.length()>3500)return;
                           try{
                               JSONObject obj=new JSONObject(body);
                               String kind=obj.optString("kind","");
                               if(!kind.matches("frame|iframe|source|resource|fetch|xhr|play|metadata|media-error|js-error"))return;
                               String value=obj.optString("url","");
                               String detail=obj.optString("detail","");
                               if(value.length()>2100||detail.length()>120)return;
                               String origin=sourceOrigin==null?"":sourceOrigin.toString();
                               event(kind.toUpperCase(Locale.ROOT),value,
                                  (isMainFrame?"principal":"iframe")+" "+origin+" "+detail);
                               if("frame".equals(kind))frames++;
                               if("media-error".equals(kind)||"js-error".equals(kind))errors++;
                               if(looksMedia(value)){mediaHits++;found.accept(value);}
                           }catch(Exception ignored){}
                        }
                    });
                frameSupported=true;
            }
        }catch(Exception e){event("SISTEMA","","WebMessage no disponible: "+e.getClass().getSimpleName());}
        try{
            if(!js.isEmpty() && frameSupported &&
               WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){
                WebViewCompat.addDocumentStartJavaScript(web,js,Collections.singleton("*"));
                scriptSupported=true;
            }
        }catch(Exception e){event("SISTEMA","","Inicio de documento no disponible");}
        event("SISTEMA","","Monitor de marcos: "+(scriptSupported?"activo":"limitado a página principal"));
    }
    public void onPageFinished(String url){
        event("PÁGINA",url,"terminó la carga");
        if(!scriptSupported&&!js.isEmpty()){
            web.evaluateJavascript(js,null);
        }
    }
    public void recordNetwork(String url,boolean isMain){
        if(!looksMedia(url)&&!looksPlayer(url))return;
        synchronized(this){nativeHits++;}
        event("SOLICITUD",url,isMain?"documento":"recurso / iframe");
    }
    private static boolean looksMedia(String url){
        if(url==null)return false;
        String low=url.toLowerCase(Locale.ROOT);
        return low.matches("(?s).*\\.(mp4|m4v|webm|m3u8|mpd|mp3|m4a)([/#?]|$).*")
              ||low.contains("/get_file/");
    }
    private static boolean looksPlayer(String url){
        if(url==null)return false;
        String low=url.toLowerCase(Locale.ROOT);
        return low.contains("/player/index.php")||low.contains("/embed/");
    }
    public void clear(){ synchronized(this){events.clear();seen.clear();mediaHits=0;frames=0;nativeHits=0;errors=0;} }
    private void event(String type,String url,String detail){
        String safe=safeUrl(url);
        String line=type+" | "+safe+" | "+shorten(detail,140);
        synchronized(this){
            if(!seen.add(line))return;
            while(events.size()>=MAX_EVENTS)events.removeFirst();
            events.addLast(line);
        }
    }
    private static String shorten(String s,int limit){
        if(s==null)return "";
        String cleaned=s.replace('\n',' ').replace('\r',' ').replace('\t',' ');
        return cleaned.length()>limit?cleaned.substring(0,limit)+"…":cleaned;
    }
    private static String safeUrl(String url){
        if(url==null||url.trim().isEmpty())return "";
        try{
            Uri u=Uri.parse(url);
            if(!"https".equalsIgnoreCase(u.getScheme())&&!"http".equalsIgnoreCase(u.getScheme()))return shorten(u.getScheme()+":",40);
            // Never export query strings or fragments, which can hold access tokens.
            Uri clean=u.buildUpon().encodedQuery(null).fragment(null).build();
            return shorten(clean.toString(),260);
        }catch(Exception e){return "[URL omitida]";}
    }
    public synchronized int size(){return events.size();}
    public synchronized String getStatus(){
        return "Solicitudes "+nativeHits+" · Eventos multimedia "+mediaHits+
          " · Marcos "+frames+" · Errores "+errors+
          "\nMonitor de iframes: "+(scriptSupported?"activo":"no disponible en este WebView");
    }
    public synchronized String preview(){
        StringBuilder out=new StringBuilder();
        int skip=Math.max(0,events.size()-45),i=0;
        for(String e:events){if(i++<skip)continue;out.append("• ").append(e).append("\n\n");}
        return out.toString().trim();
    }
    public synchronized String report(String current){
        StringBuilder out=new StringBuilder();
        out.append("VideoDetect - Diagnóstico de medios\n")
           .append("Versión: 0.5 (prueba)\n")
           .append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n")
           .append("Sitio: ").append(safeUrl(current)).append("\n")
           .append("Monitor de iframe: ").append(scriptSupported?"sí":"no").append("\n")
           .append(getStatus()).append("\n\n")
           .append("Las consultas de URL fueron eliminadas.\n");
        for(String e:events)out.append(e).append("\n");
        return out.toString();
    }
}
