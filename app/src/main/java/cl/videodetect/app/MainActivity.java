package cl.videodetect.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.regex.*;
import java.io.*;

public class MainActivity extends Activity {
 private WebView web;
 private EditText address;
 private LinearLayout root, panel;
 private final LinkedHashMap<String,String> links=new LinkedHashMap<>();
 private final Set<String> adHosts=new HashSet<>(Arrays.asList("doubleclick.net","googlesyndication.com","googleadservices.com","popads.net","popcash.net","exoclick.com","exosrv.com","adsterra.com","onclickads.net","propellerads.com","trafficjunky.net","juicyads.com"));
 private final String script="(function(){let a=[];document.querySelectorAll('video,source').forEach(x=>{if(x.src)a.push(x.src);if(x.currentSrc)a.push(x.currentSrc)});return JSON.stringify(a);})()";
 @Override public void onCreate(Bundle state){
  super.onCreate(state); root=new LinearLayout(this);root.setOrientation(1);setContentView(root);
  LinearLayout tabs=new LinearLayout(this);tabs.setBackgroundColor(Color.rgb(25,29,42));root.addView(tabs);
  button(tabs,"Navegador",()->showBrowser());button(tabs,"Detectados",()->showLinks());
  panel=new LinearLayout(this);panel.setOrientation(1);root.addView(panel,new LinearLayout.LayoutParams(-1,0,1));
  web=new WebView(this);
  WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  web.setWebChromeClient(new WebChromeClient(){@Override public boolean onCreateWindow(WebView w,boolean d,boolean g,Message m){return false;}});
  web.setWebViewClient(new WebViewClient(){
   @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){
    Uri uri=r.getUrl();String host=uri.getHost();
    if(isAd(host))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
    detect(uri.toString());return super.shouldInterceptRequest(w,r);
   }
   @Override public void onPageFinished(WebView v,String url){if(address!=null)address.setText(url);scan();}
   @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){
    String scheme=r.getUrl().getScheme();return !("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme));
   }
  });
  web.setDownloadListener((url,ua,disposition,mime,len)->{detect(url);askDownload(url);});
  showBrowser();web.loadUrl("https://example.com");
 }
 private void button(LinearLayout row,String name,Runnable fn){
  Button b=new Button(this);b.setText(name);b.setAllCaps(false);row.addView(b,new LinearLayout.LayoutParams(0,52,1));b.setOnClickListener(v->fn.run());
 }
 private void showBrowser(){
  panel.removeAllViews();LinearLayout bar=new LinearLayout(this);address=new EditText(this);address.setSingleLine();address.setHint("Dirección web");address.setText(web.getUrl()==null?"":web.getUrl());bar.addView(address,new LinearLayout.LayoutParams(0,56,1));
  button(bar,"Ir",()->navigate());panel.addView(bar);
  LinearLayout opts=new LinearLayout(this);button(opts,"Atrás",()->{if(web.canGoBack())web.goBack();});button(opts,"Escanear",()->scan());button(opts,"Archivos ("+links.size()+")",()->showLinks());panel.addView(opts);
  if(web.getParent()!=null)((android.view.ViewGroup)web.getParent()).removeView(web);
  panel.addView(web,new LinearLayout.LayoutParams(-1,0,1));
 }
 private void navigate(){
  String x=address.getText().toString().trim();if(!x.matches("(?i)^https?://.*")){
   if(x.contains(".")&&!x.contains(" "))x="https://"+x;else x="https://www.google.com/search?q="+Uri.encode(x);
  }
  web.loadUrl(x);
 }
 private boolean isAd(String host){
  if(host==null)return false;host=host.toLowerCase(Locale.ROOT);
  for(String h:adHosts)if(host.equals(h)||host.endsWith("."+h))return true;return false;
 }
 private String classify(String url){
  String path=url.split("[?#]",2)[0].toLowerCase(Locale.ROOT);
  if(path.matches(".*\\.(mp4|m4v)$"))return "MP4";
  if(path.endsWith(".webm"))return "WebM";
  if(path.endsWith(".m3u8"))return "HLS";
  if(path.endsWith(".mpd"))return "DASH";
  if(path.matches(".*\\.(mp3|m4a|aac|wav|flac)$"))return "Audio";
  return null;
 }
 private void detect(String url){
  if(url==null||!url.startsWith("http"))return;String kind=classify(url);
  if(kind!=null)runOnUiThread(()->{if(!links.containsKey(url))links.put(url,kind);});
 }
 private void scan(){
  web.evaluateJavascript(script,js->{
   try {
    String unescaped=js;
    if(unescaped.startsWith("\""))unescaped=new org.json.JSONTokener(unescaped).nextValue().toString();
    org.json.JSONArray a=new org.json.JSONArray(unescaped);
    for(int i=0;i<a.length();i++)detect(a.optString(i));
    Toast.makeText(this,links.size()+" archivos detectados",Toast.LENGTH_SHORT).show();
   }catch(Exception e){Toast.makeText(this,"Escaneo completado",Toast.LENGTH_SHORT).show();}
  });
 }
 private void showLinks(){
  panel.removeAllViews();TextView heading=new TextView(this);heading.setText("Archivos detectados: "+links.size());heading.setTextSize(22);heading.setPadding(18,18,18,12);panel.addView(heading);
  LinearLayout actions=new LinearLayout(this);button(actions,"Actualizar",()->{scan();showLinks();});button(actions,"Limpiar",()->{links.clear();showLinks();});panel.addView(actions);
  ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  if(links.isEmpty()){TextView info=new TextView(this);info.setText("Navega y reproduce un video, luego pulsa Escanear. Se detectan MP4, WebM, HLS y DASH cuando aparecen en las solicitudes o elementos del navegador.");info.setPadding(20,20,20,20);list.addView(info);}
  for(Map.Entry<String,String> e:new ArrayList<>(links.entrySet())){
   String url=e.getKey();String kind=e.getValue();String name=Uri.parse(url).getLastPathSegment();
   TextView t=new TextView(this);t.setText(kind+"   "+(name==null?url:name)+"\n"+url);t.setTextIsSelectable(true);t.setPadding(18,16,18,16);list.addView(t);
   LinearLayout row=new LinearLayout(this);button(row,"Descargar",()->askDownload(url));button(row,"Copiar",()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Media URL",url));Toast.makeText(this,"Copiado",Toast.LENGTH_SHORT).show();});list.addView(row);
  }
 }
 private void askDownload(String url){
  String kind=classify(url);if(kind==null||kind.equals("HLS")||kind.equals("DASH")){
   new AlertDialog.Builder(this).setTitle("Lista multimedia").setMessage("Esta versión detecta HLS/DASH, pero todavía no transforma sus fragmentos en MP4. Puedes copiar la URL para utilizarla en un reproductor compatible.").setPositiveButton("OK",null).show();return;
  }
  new AlertDialog.Builder(this).setTitle("Descargar "+kind).setMessage(Uri.parse(url).getLastPathSegment()).setNegativeButton("Cancelar",null).setPositiveButton("Descargar",(d,w)->download(url)).show();
 }
 private void download(String url){
  try{
   DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
   req.setTitle("VideoDetect multimedia");req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
   req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"videodetect_"+System.currentTimeMillis()+"."+("WebM".equals(classify(url))?"webm":"mp4"));
   String cookies=CookieManager.getInstance().getCookie(url);
   if(cookies!=null)req.addRequestHeader("Cookie",cookies);
   req.addRequestHeader("User-Agent",web.getSettings().getUserAgentString());
   ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
   Toast.makeText(this,"Descarga iniciada",Toast.LENGTH_LONG).show();
  }catch(Exception e){new AlertDialog.Builder(this).setMessage("No se pudo iniciar la descarga: "+e.getMessage()).setPositiveButton("OK",null).show();}
 }
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}