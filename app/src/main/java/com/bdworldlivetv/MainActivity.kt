package com.bdworldlivetv
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class Channel(val name:String,val url:String,val group:String)
class MainActivity:AppCompatActivity(){
 private val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
 private val sources=listOf(
  "https://raw.githubusercontent.com/saeidrahmanbd/BDIX-IPTV/main/IPTV-Playlist.m3u",
  "https://iptv-org.github.io/iptv/index.m3u",
  "https://raw.githubusercontent.com/AdeelWajid/iptv_validated_list/main/playlist.m3u")
 private val channels=mutableListOf<Channel>()
 private lateinit var list:LinearLayout
 private lateinit var status:TextView
 private lateinit var pv:PlayerView
 private var player:ExoPlayer?=null
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=1;setPadding(16,16,16,16);setBackgroundColor(0xFF090D18.toInt())}
  val title=TextView(this).apply{text="BD WORLD LIVE TV";textSize=23f;setTextColor(-1);gravity=Gravity.CENTER}
  status=TextView(this).apply{setTextColor(0xFF9AA4B2.toInt());text="Loading playlists…"}
  pv=PlayerView(this).apply{layoutParams=LinearLayout.LayoutParams(-1,220);useController=true}
  val refresh=Button(this).apply{text="Refresh channels";setOnClickListener{loadSources()}}
  list=LinearLayout(this).apply{orientation=1}
  val scroll=ScrollView(this).apply{addView(list)}
  root.addView(title);root.addView(status);root.addView(pv);root.addView(refresh);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  setContentView(root)
  player=ExoPlayer.Builder(this).build();pv.player=player
  player?.addListener(object:Player.Listener{
   override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_READY)status.text="Playing";if(state==Player.STATE_BUFFERING)status.text="Buffering…"}
   override fun onPlayerError(error:PlaybackException){status.text="This stream failed. Try another channel."}
  })
  loadSources()
 }
 private fun loadSources(){
  status.text="Loading all playlist sources…"
  Thread{
   val unique=linkedMapOf<String,Channel>()
   sources.forEach{src->try{
    val body=http.newCall(Request.Builder().url(src).build()).execute().use{it.body?.string().orEmpty()}
    var meta=""
    body.lineSequence().forEach{raw->
     val line=raw.trim()
     if(line.startsWith("#EXTINF"))meta=line
     else if((line.startsWith("http://")||line.startsWith("https://"))&&meta.isNotBlank()){
      val nm=meta.substringAfterLast(",").trim().ifBlank{"Unnamed"}
      val grp=Regex("group-title=\"([^\"]+)\"").find(meta)?.groupValues?.get(1)?:"Other"
      unique.putIfAbsent(line,Channel(nm,line,grp));meta=""
     }
    }
   }catch(_:Exception){}}
   runOnUiThread{
    channels.clear();channels.addAll(unique.values);list.removeAllViews()
    channels.take(500).forEach{ch->list.addView(Button(this).apply{text=ch.name+" • "+ch.group;setOnClickListener{play(ch)}})}
    status.text="${channels.size} channel links loaded. Play to verify."
   }
  }.start()
 }
 private fun play(ch:Channel){status.text="Trying: "+ch.name;player?.setMediaItem(MediaItem.fromUri(ch.url));player?.prepare();player?.playWhenReady=true}
 override fun onStop(){super.onStop();player?.pause()}
 override fun onDestroy(){pv.player=null;player?.release();player=null;super.onDestroy()}
}
