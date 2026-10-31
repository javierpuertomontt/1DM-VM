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
      
      // These events expose codec and resolution metadata only, never raw video data.
      document.addEventListener("resize",function(e){
        var t=e.target;
        if(t&&t.tagName==="VIDEO")post("resize",t.currentSrc||t.src||location.href,
          String(t.videoWidth||0)+"x"+String(t.videoHeight||0));
      },true);
      document.addEventListener("encrypted",function(e){
        var t=e.target;
        post("encrypted",t&&(t.currentSrc||t.src)||location.href,
          String(e.initDataType||"unknown"));
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

    // Record whether the player feeds media through MSE and which MIME codecs it requests.
    // Keep native return values and arguments unchanged. Never read segment bytes or keys.
    var MSE=window.MediaSource;
    if(MSE&&MSE.prototype&&typeof MSE.prototype.addSourceBuffer==="function"){
      var bufferTypes=new WeakMap(),bufferCounts=new WeakMap();
      var originalBuffer=MSE.prototype.addSourceBuffer;
      MSE.prototype.addSourceBuffer=function(mime){
        var buffer=originalBuffer.apply(this,arguments);
        try{bufferTypes.set(buffer,String(mime));post("mse",location.href,String(mime));}catch(e){}
        return buffer;
      };
      if(window.SourceBuffer&&SourceBuffer.prototype&&typeof SourceBuffer.prototype.appendBuffer==="function"){
        var originalAppend=SourceBuffer.prototype.appendBuffer;
        SourceBuffer.prototype.appendBuffer=function(bytes){
          try{
            var count=(bufferCounts.get(this)||0)+1;
            bufferCounts.set(this,count);
            if(count<=3||count===10||count%25===0){
              var size=bytes&&typeof bytes.byteLength==="number"?bytes.byteLength:-1;
              post("mse-append",location.href,
                "buffer="+String(bufferTypes.get(this)||"unknown")+
                " count="+count+" bytes="+size);
            }
          }catch(e){}
          return originalAppend.apply(this,arguments);
        };
      }
    }
    var originalFetch=window.fetch;
    if(typeof originalFetch==="function")window.fetch=function(input){
      var requestUrl="";
      try{requestUrl=typeof input==="string"?input:input&&input.url||"";post("fetch",requestUrl,"fetch");}catch(e){}
      var promise=originalFetch.apply(this,arguments);
      try{
        if(promise&&typeof promise.then==="function"&&requestUrl.indexOf("/cdn/down/")!==-1){
          promise.then(function(response){
            try{
              var type=response.headers.get("content-type")||"(sin tipo)";
              var size=response.headers.get("content-length")||"?";
              post("fetch-response",requestUrl,
                "HTTP "+response.status+" tipo="+type+" tamaño="+size);
            }catch(e){}
          },function(error){post("fetch-failed",requestUrl,"solicitud rechazada");});
        }
      }catch(e){}
      return promise;
    };
    var oldOpen=XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open=function(method,url){
      try{post("xhr",url,String(method));}catch(e){}
      return oldOpen.apply(this,arguments);
    };
  }catch(e){}
})();