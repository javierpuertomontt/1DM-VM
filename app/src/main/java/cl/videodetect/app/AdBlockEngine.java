package cl.videodetect.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight WebView filter engine. Supports ABP hostname anchors, simple
 * substring rules, hostname exceptions and a SAFE SUBSET of cosmetic rules.
 * Not a full replacement for the Adblock Plus / uBO rules engine.
 */
public final class AdBlockEngine {
    private static final String[] SOURCES = {
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://filters.adtidy.org/extension/ublock/filters/19.txt"
    };
    private static final String[] CACHE = {"easylist.txt","easyprivacy.txt","adguard-popups.txt"};
    private static final Set<String> BUILTIN = new HashSet<>(Arrays.asList(
        "doubleclick.net","googlesyndication.com","googleadservices.com",
        "adservice.google.com","exoclick.com","exosrv.com","popads.net",
        "popcash.net","onclickads.net","propellerads.com","adsterra.com",
        "trafficjunky.net","juicyads.com","adskeeper.co.uk","pushground.com",
        "onclickmega.com","hilltopads.net","a-ads.com","ad-maven.com",
        "clickadu.com","adnetworkperformance.com","onclkds.com",
        "jads.co","ero-advertising.com","tsyndicate.com","realsrv.com",
        "syndication.exoclick.com","ad-delivery.net","adnxs.com",
        "revcontent.com","taboola.com","outbrain.com","popunder.net",
        "trafficstars.com","adsrvr.org","adform.net","bongacams.com"));
    private final Context context;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile Set<String> domainRules=Collections.emptySet();
    private volatile Set<String> exceptionDomains=Collections.emptySet();
    private volatile List<String> urlRules=Collections.emptyList();
    private volatile List<String> genericCss=Collections.emptyList();
    private volatile Map<String,List<String>> siteCss=Collections.emptyMap();
    private final AtomicInteger blocked=new AtomicInteger();
    private volatile boolean enabled;
    private volatile int loadedCount=0;
    private volatile String state="Filtros integrados activos";
    private SharedPreferences preferences;
    private final Runnable onUpdate;

    public AdBlockEngine(Context context, Runnable onUpdate){
        this.context=context.getApplicationContext();
        this.onUpdate=onUpdate;
        preferences=this.context.getSharedPreferences("adblock", Context.MODE_PRIVATE);
        enabled=preferences.getBoolean("enabled",true);
        worker.execute(() -> {
            loadFromCache();
            long updated=preferences.getLong("updated",0);
            if(System.currentTimeMillis()-updated>24*60*60*1000L || loadedCount<1000)refreshNow();
        });
    }
    public boolean isEnabled(){return enabled;}
    public int getBlockedCount(){return blocked.get();}
    public int getLoadedCount(){return loadedCount+BUILTIN.size();}
    public String getState(){return state;}
    public void setEnabled(boolean b){
        enabled=b;preferences.edit().putBoolean("enabled",b).apply();notifyChange();
    }
    public void refresh(){worker.execute(this::refreshNow);}
    private void notifyChange(){if(onUpdate!=null)ui.post(onUpdate);}
    private void loadFromCache(){
        RuleBuilder builder=new RuleBuilder();
        boolean found=false;
        for(String path:CACHE){
            File file=new File(context.getFilesDir(),path);
            if(!file.exists())continue;
            try(BufferedReader rd=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
                String line;found=true;
                while((line=rd.readLine())!=null)builder.add(line);
            }catch(Exception ignored){}
        }
        if(found)publish(builder,"Filtros actualizados cargados");
    }
    private void refreshNow(){
        state="Actualizando filtros…";notifyChange();
        RuleBuilder builder=new RuleBuilder();
        int good=0;
        for(int i=0;i<SOURCES.length;i++){
            String data=download(SOURCES[i]);
            if(data==null){
                File file=new File(context.getFilesDir(),CACHE[i]);
                if(file.exists())try{
                    data=readFile(file);
                }catch(Exception ignored){}
            }else{
                try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),CACHE[i]))){
                    out.write(data.getBytes(StandardCharsets.UTF_8));
                }catch(Exception ignored){}
                good++;
            }
            if(data!=null)for(String line:data.split("\\r?\\n"))builder.add(line);
        }
        if(builder.networkCount()>50){
            publish(builder,"Filtros activos: EasyList, EasyPrivacy, AdGuard Popups");
            if(good>0)preferences.edit().putLong("updated",System.currentTimeMillis()).apply();
        }else{
            state="Filtros integrados activos (sin conexión a listas)";
            notifyChange();
        }
    }
    private String readFile(File file)throws Exception{
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))>0)out.write(buf,0,n);
            return out.toString("UTF-8");
        }
    }
    private String download(String url){
        HttpURLConnection conn=null;
        try {
            conn=(HttpURLConnection)new URL(url).openConnection();
            conn.setConnectTimeout(9000);conn.setReadTimeout(14000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent","VideoDetect/0.3 filter updater");
            if(conn.getResponseCode()!=200)return null;
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n,total=0;
                while((n=in.read(b))!=-1){
                    total+=n;if(total>7_000_000)return null;
                    out.write(b,0,n);
                }
                return out.toString("UTF-8");
            }
        }catch(Exception ignored){return null;}
        finally{if(conn!=null)conn.disconnect();}
    }
    private void publish(RuleBuilder builder,String message){
        domainRules=Collections.unmodifiableSet(new HashSet<>(builder.hosts));
        exceptionDomains=Collections.unmodifiableSet(new HashSet<>(builder.except));
        urlRules=Collections.unmodifiableList(new ArrayList<>(builder.urls));
        genericCss=Collections.unmodifiableList(new ArrayList<>(builder.cosmetic));
        Map<String,List<String>> siteMap=new HashMap<>();
        for(Map.Entry<String,List<String>> e:builder.site.entrySet())
            siteMap.put(e.getKey(),Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        siteCss=Collections.unmodifiableMap(siteMap);
        loadedCount=builder.networkCount();state=message;notifyChange();
    }
    private static final class RuleBuilder {
        final Set<String> hosts=new HashSet<>();
        final Set<String> except=new HashSet<>();
        final Set<String> urls=new HashSet<>();
        final Set<String> cosmetic=new LinkedHashSet<>();
        final Map<String,List<String>> site=new HashMap<>();
        int networkCount(){return hosts.size()+urls.size();}
        void add(String raw){
            if(raw==null)return;
            String s=raw.trim();
            if(s.isEmpty()||s.startsWith("!")||s.startsWith("[")||s.startsWith("#"))return;
            if(s.contains("#@#")||s.contains("#?#")||s.contains("#$#"))return;
            int cosmeticIndex=s.indexOf("##");
            if(cosmeticIndex>=0){
                String prefix=s.substring(0,cosmeticIndex);
                String selector=s.substring(cosmeticIndex+2);
                if(!safeCss(selector))return;
                if(prefix.isEmpty()){
                    if(cosmetic.size()<2500 && relevantCss(selector))cosmetic.add(selector);
                }else if(!prefix.contains("~") && !prefix.contains(",") && prefix.length()<100){
                    List<String> list=site.computeIfAbsent(prefix.toLowerCase(Locale.ROOT),x->new ArrayList<>());
                    if(list.size()<250)list.add(selector);
                }
                return;
            }
            if(s.startsWith("@@||")){
                String host=parseHost(s.substring(4));
                if(host!=null)except.add(host);
                return;
            }
            if(s.startsWith("||")){
                String host=parseHost(s.substring(2));
                if(host!=null)hosts.add(host);
                return;
            }
            if(s.startsWith("@@"))return;
            if(s.contains("$")||s.contains("*")||s.startsWith("/")||s.contains("|")||s.contains("^"))return;
            // High signal URL path fragments only, never generic 'ad' substring.
            if(s.length()>=12&&s.length()<=110&&s.contains("/")&&
               (s.contains("popup")||s.contains("popunder")||s.contains("/ads/")||s.contains("adserver")))
                if(urls.size()<400)urls.add(s.toLowerCase(Locale.ROOT));
        }
        private String parseHost(String s){
            int e=s.length();
            for(char x:new char[]{'^','/','$','*','|'}){int i=s.indexOf(x);if(i>=0)e=Math.min(e,i);}
            String host=s.substring(0,e).toLowerCase(Locale.ROOT);
            if(host.length()<5||host.length()>120||!host.contains(".")||!host.matches("[a-z0-9.-]+"))return null;
            return host;
        }
        private boolean safeCss(String s){
            return s.length()>2&&s.length()<200&&s.indexOf('{')<0&&s.indexOf('}')<0&&
                s.indexOf('\n')<0&& !s.contains(":style(")&&!s.contains(":xpath(")&&
                !s.contains(":-abp")&&!s.contains(":has-text");
        }
        private boolean relevantCss(String s){
            String lower=s.toLowerCase(Locale.ROOT);
            return lower.contains("advert")||lower.contains("banner")||lower.contains("sponsor")
                ||lower.contains("popup")||lower.contains("popunder")||lower.contains("ad-container")
                ||lower.contains("adsbygoogle")||lower.contains("adslot")||lower.contains("ad-slot");
        }
    }
    private boolean matchesHost(Set<String> list,String host){
        String part=host;
        while(true){
            if(list.contains(part))return true;
            int dot=part.indexOf('.');
            if(dot<0)break;
            part=part.substring(dot+1);
        }
        return false;
    }
    public boolean blocks(String url){
        if(!enabled||url==null||!url.startsWith("http"))return false;
        Uri u;
        try{u=Uri.parse(url);}catch(Exception e){return false;}
        String host=u.getHost();
        if(host==null)return false;
        host=host.toLowerCase(Locale.ROOT);
        // Keep the actual embedded player reachable.
        if(host.equals("moviekh.xtremestream.xyz"))return false;
        String lower=url.toLowerCase(Locale.ROOT);
        boolean blockedByRule=
            (host.equals("pervtube.net") && (lower.contains("/wp-content/xsad/") || lower.contains("/wp-content/xsad")))
            || (host.equals("ccu.xtremestream.xyz")&&lower.contains("/x.php"))
            || matchesHost(BUILTIN,host)
            || (!matchesHost(exceptionDomains,host) && matchesHost(domainRules,host));
        if(!blockedByRule)
            for(String pattern:urlRules)if(lower.contains(pattern)){blockedByRule=true;break;}
        if(blockedByRule)this.blocked.incrementAndGet();
        return blockedByRule;
    }
    public String cosmeticCss(String host){
        if(!enabled)return "";
        StringBuilder sb=new StringBuilder();
        sb.append("iframe[src*='doubleclick'],iframe[src*='popads'],iframe[src*='exoclick'],")
          .append("ins.adsbygoogle,[id^='google_ads'],.adsbygoogle")
          .append("{display:none!important;visibility:hidden!important;}");
        int count=0;
        for(String selector:genericCss){
            if(count++>500||sb.length()>80000)break;
            sb.append(selector).append("{display:none!important;visibility:hidden!important;}");
        }
        if(host!=null){
            String part=host.toLowerCase(Locale.ROOT);
            while(true){
                List<String> specific=siteCss.get(part);
                if(specific!=null)for(String selector:specific){
                    if(sb.length()>95000)break;
                    sb.append(selector).append("{display:none!important;visibility:hidden!important;}");
                }
                int dot=part.indexOf('.');
                if(dot<0)break;
                part=part.substring(dot+1);
            }
            if(host.equalsIgnoreCase("pervtube.net")||host.endsWith(".pervtube.net")){
                sb.append("iframe[src*='redirect.xtremestream.xyz'],a[href*='redirect.xtremestream.xyz'],")
                  .append("[id^='xsad'],[class*='xsad'],.ad-container,.advertisement,")
                  .append("[id*='popunder'],[class*='popunder']")
                  .append("{display:none!important;visibility:hidden!important;}");
            }
        }
        return sb.toString();
    }
}
