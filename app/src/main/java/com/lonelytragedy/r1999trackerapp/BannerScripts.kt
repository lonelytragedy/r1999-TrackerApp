package com.lonelytragedy.r1999trackerapp

object BannerScripts {
    const val EXTRACT = """
(function(){
  try{
    if(typeof ACTIVE_BANNERS==='undefined')return '[]';
    var out=[];
    ACTIVE_BANNERS.forEach(function(b){
      var s=Date.parse(b.startUTC),e=Date.parse(b.endUTC);
      if(isNaN(s)||isNaN(e))return;
      var info=(typeof BANNERS!=='undefined'&&BANNERS[b.key])||{};
      var r6=(info.rateUp6&&info.rateUp6.length)?info.rateUp6:[];
      out.push({key:b.key,name:info.name||b.key,type:info.type||'',rate6:r6,
        rate:r6.length?r6:(b.rateUp||[]),image:b.image||'',start:s,end:e});
    });
    return JSON.stringify(out);
  }catch(err){return '[]';}
})()
"""
}
