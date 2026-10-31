(function(){
  if(window.__vdMonitorInstalled)return;
  window.__vdMonitorInstalled=true;
  var seen={};var sent=0;
  function post(kind,url,extra){
    if(sent>240||!window.VideoDetectMonitor||!VideoDetectMonitor.postMessage)return;
    try{
      url=typeof url==="string"?url:"";
      if(url.length>2000)url=url.slice(0,2000);
      var key=kind+"|"+url+"|"+String(extra||"");
      if(seen[key])return;seen[key]=1;sent++;
      VideoDetectMonitor.postMessage(JSON.stringify({kind:kind,url:url,detail:String(extra||"").slice(0,100),frame:location.origin}));
    }catch(e){}
  }
  try{post("frame",location.href,"marco iniciado");}catch(e){}
  function observeVideo(n){
    if(!n||n.nodeType!==1)return;
    try{
      var tag=n.tagName.toLowerCase();
      if(tag==="video"||tag==="audio"||tag==="source"){
        var u=n.currentSrc||n.src||n.getAttribute("src")||n.getAttribute("data-src");
        if(u)post("source",u,tag);
      }
      if(tag==="iframe"&&n.src)post("iframe",n.src,"iframe");
      if(n.querySelectorAll)n.querySelectorAll("video,audio,source,iframe").forEach(observeVideo);
    }catch(e){}
  }
  function scan(){
    try{document.querySelectorAll("video,audio,source,iframe").forEach(observeVideo);}catch(e){}
    try{
      performance.getEntriesByType("resource").forEach(function(item){
        if(/\.(mp4|webm|m3u8|mpd|m4v)(?:[/?#]|$)|\/get_file\//i.test(item.name))post("resource",item.name,"performance");
      });
    }catch(e){}
  }
  function start(){
    try{
      scan();
      new MutationObserver(function(items){
        items.forEach(function(item){
          if(item.type==="attributes")observeVideo(item.target);
          else item.addedNodes.forEach(observeVideo);
        });
      }).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:["src","data-src"]});
      document.addEventListener("play",function(e){
        var t=e.target;if(t&&t.tagName){
          post("play",t.currentSrc||t.src||location.href,t.tagName);
          setTimeout(scan,700);
        }
      },true);
      document.addEventListener("loadedmetadata",function(e){
        var t=e.target;
        if(t&&t.tagName)post("metadata",t.currentSrc||t.src||location.href,
           String(t.videoWidth||0)+"x"+String(t.videoHeight||0));
      },true);
      document.addEventListener("error",function(e){
        var t=e.target;if(t&&t.tagName&&/^(VIDEO|AUDIO|SOURCE)$/i.test(t.tagName))
          post("media-error",t.currentSrc||t.src||location.href,t.tagName);
      },true);
      if(window.PerformanceObserver){
        var o=new PerformanceObserver(function(list){
          list.getEntries().forEach(function(x){
            if(/\.(mp4|webm|m3u8|mpd|m4v)(?:[/?#]|$)|\/get_file\//i.test(x.name))
               post("resource",x.name,"timing");
          });
        });
        try{o.observe({entryTypes:["resource"]});}catch(e){}
      }
    }catch(e){post("js-error",location.href,String(e));}
  }
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",start,{once:true});
  else start();
  try{
    var originalFetch=window.fetch;
    if(typeof originalFetch==="function")window.fetch=function(input){
      try{post("fetch",typeof input==="string"?input:input.url,"fetch");}catch(e){}
      return originalFetch.apply(this,arguments);
    };
    var oldOpen=XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open=function(method,url){
      try{post("xhr",url,String(method));}catch(e){}
      return oldOpen.apply(this,arguments);
    };
  }catch(e){}
})();