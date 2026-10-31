(function(){
  if(window.__vdMonitorInstalled)return;
  window.__vdMonitorInstalled=true;
  var seen={};var sent=0;
  // Capture only after explicit opt-in from the native app. A tiny
  // reply-acknowledged message bridge provides backpressure so full buffer
  // data is never accumulated in a giant JS -> Android string.
  var capSession="",capEnabled=false,capQueue=[],capQueuedBytes=0,
      capCurrent=null,capPending=0,capSeq=0,capSending=false,
      capBufferLimit=24*1024*1024;
  function capturePoll(){
    try{
      if(window.VideoDetectMonitor&&VideoDetectMonitor.postMessage)
        VideoDetectMonitor.postMessage(JSON.stringify({kind:"capture-poll"}));
    }catch(e){}
  }
  function captureReset(session){
    capSession=session;capQueue=[];capQueuedBytes=0;
    capCurrent=null;capPending=0;capSeq=0;capSending=false;
  }
  function capturePump(){
    if(capSending||!capSession||!capQueue.length)return;
    var item=capQueue[0];
    if(item.session!==capSession){capQueuedBytes-=item.data.length;capQueue.shift();setTimeout(capturePump,0);return;}
    if(item.position>=item.data.length){
      capQueuedBytes-=item.data.length;capQueue.shift();setTimeout(capturePump,0);return;
    }
    capCurrent=item;capSending=true;capSeq++;
    capPending=capSeq;
    try{
      var data=item.data.subarray(item.position,Math.min(item.position+12000,item.data.length));
      var bin="";
      for(var i=0;i<data.length;i+=4096)
        bin+=String.fromCharCode.apply(null,data.subarray(i,Math.min(i+4096,data.length)));
      VideoDetectMonitor.postMessage(JSON.stringify({
        kind:"capture-chunk",session:capSession,seq:capPending,track:item.track,base64:btoa(bin)
      }));
    }catch(e){capEnabled=false;capSending=false;capQueue=[];capQueuedBytes=0;}
  }
  function captureBuffer(track,bytes){
    if(!capEnabled||!capSession||!bytes)return;
    try{
      var view;
      if(bytes instanceof ArrayBuffer)view=new Uint8Array(bytes);
      else if(ArrayBuffer.isView(bytes))view=new Uint8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength);
      else return;
      if(!view.byteLength||view.byteLength>8*1024*1024)return;
      if(capQueuedBytes+view.byteLength>capBufferLimit){capEnabled=false;return;}
      var copy=view.slice();
      capQueue.push({session:capSession,data:copy,track:track,position:0});
      capQueuedBytes+=copy.length;
      capturePump();
    }catch(e){capEnabled=false;}
  }

  // Quality selection is best-effort: only click normal player quality controls.
  // This does not force an unavailable rendition or access protected streams.
  var capPreferredQuality="auto",qualityWasClicked="",qualityAttempts=0;
  function qualityOptions(){
    var opts=[];
    try{
      document.querySelectorAll("select option, [role=option], .vjs-menu-item, .jw-settings-content-item, .plyr__menu__container button, [class*=quality] button, [class*=quality] li, [class*=resolution] button, [data-quality], button, li")
        .forEach(function(n){
          if(opts.length>100)return;
          var text=(n.textContent||"").trim().toLowerCase().replace(/\s+/g," ");
          if(!/^(?:240|360|480|720|1080|1440|2160)p?(?:\s*(?:hd|fhd|4k))?$/.test(text))return;
          opts.push({node:n,height:parseInt(text,10)});
        });
    }catch(e){}
    return opts;
  }
  function selectPreferredQuality(){
    if(!capEnabled||!capSession||qualityWasClicked)return;
    var host="";
    try{host=location.hostname.toLowerCase();}catch(e){}
    if(host.indexOf("xtremestream.xyz")<0)return;
    if(qualityAttempts++>25)return;
    try{
      var opts=qualityOptions();
      if(!opts.length)return;
      var desired=capPreferredQuality==="auto"?
        Math.max.apply(null,opts.map(function(o){return o.height;})):
        parseInt(capPreferredQuality,10);
      var match=opts.find(function(o){return o.height===desired;});
      if(!match)return;
      var n=match.node;
      if(n.tagName==="OPTION"){
        var select=n.parentElement;
        if(select&&select.tagName==="SELECT"){
          select.value=n.value;
          select.dispatchEvent(new Event("input",{bubbles:true}));
          select.dispatchEvent(new Event("change",{bubbles:true}));
        }
      }else if(n.offsetWidth>0&&n.offsetHeight>0){
        n.click();
      }else return;
      qualityWasClicked=String(desired);
      post("quality-attempt",location.href,"solicitado "+desired+"p");
    }catch(e){}
  }
  setInterval(selectPreferredQuality,1100);
  if(window.VideoDetectMonitor){
    VideoDetectMonitor.onmessage=function(event){
      try{
        var m=JSON.parse(event.data);
        if(m.cmd==="capture-state"){
          if(m.session!==capSession){
            captureReset(m.session);
            post("capture-sync",location.href,"sesion detectada");
          }
          if(Boolean(m.active)!==capEnabled)
            post("capture-state",location.href,Boolean(m.active)?"activa":"inactiva");
          capEnabled=Boolean(m.active);
          var preference=String(m.quality||"auto");
          if(preference!==capPreferredQuality){
            capPreferredQuality=preference;
            qualityWasClicked="";
            qualityAttempts=0;
          }
          if(capEnabled)selectPreferredQuality();
        }else if(m.cmd==="capture-ack"&&capCurrent&&m.seq===capPending&&m.session===capSession){
          if(m.ok){
            capCurrent.position=Math.min(capCurrent.position+12000,capCurrent.data.length);
            capSending=false;capCurrent=null;capPending=0;
            setTimeout(capturePump,0);
          }else{
            capEnabled=false;capQueue=[];capQueuedBytes=0;
            capCurrent=null;capSending=false;capPending=0;
          }
        }
      }catch(e){}
    };
    capturePoll();
    setInterval(capturePoll,1000);
  }

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
          var trackType=String(bufferTypes.get(this)||"");
          var captured=null;
          if(capEnabled&&(trackType.indexOf("audio/mp4")===0||trackType.indexOf("video/mp4")===0)){
            try{
              if(bytes instanceof ArrayBuffer)captured=new Uint8Array(bytes).slice();
              else if(ArrayBuffer.isView(bytes))
                captured=new Uint8Array(bytes.buffer,bytes.byteOffset,bytes.byteLength).slice();
            }catch(e){}
          }
          var originalResult=originalAppend.apply(this,arguments);
          if(captured){
            captureBuffer(trackType.indexOf("audio/")===0?"audio":"video",captured);
            post("capture-queued",location.href,
               (trackType.indexOf("audio/")===0?"audio":"video")+" bytes="+captured.length);
          }
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
          return originalResult;
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