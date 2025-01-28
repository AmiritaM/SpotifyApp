package com.example.spotifyapp


import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.spotifyapp.ui.theme.SpotifyAppTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import com.google.gson.JsonParser
import java.io.IOException


private const val ACCESS_TOKEN = "BQBbXsSDdpLSp53K_0SmProAQpRKNvlaVgrQCwVSTvVjSb3iiQZoNpSkQPCmyma25-E8L7WpfVSF0o595eKQLzfnjBMro1IAvqxw5haG9fXpUiefDdc"


class MainActivity : ComponentActivity() {
   private lateinit var databaseReference: DatabaseReference


   override fun onCreate(savedInstanceState: Bundle?) {
       super.onCreate(savedInstanceState)


       // Initialize Firebase
       FirebaseApp.initializeApp(this)
       databaseReference = FirebaseDatabase.getInstance().reference


       setContent {
           SpotifyAppTheme {
               Surface(
                   modifier = Modifier.fillMaxSize(),
                   color = MaterialTheme.colorScheme.background
               ) {
                   SpotifySearchScreen(databaseReference)
               }
           }
       }
   }
}


@Composable
fun SpotifySearchScreen(databaseReference: DatabaseReference) {
   val context = LocalContext.current
   var artistName by remember { mutableStateOf("") }
   var trackList by remember { mutableStateOf(listOf<String>()) }
   var savedTracks by remember { mutableStateOf(listOf<Pair<String, String>>()) }
   var showSavedTracks by remember { mutableStateOf(false) }
   val addedTracks = remember { mutableStateListOf<String>() }


   Column(
       modifier = Modifier
           .fillMaxSize()
           .padding(16.dp),
       verticalArrangement = Arrangement.spacedBy(16.dp),
       horizontalAlignment = Alignment.CenterHorizontally
   ) {
       if (!showSavedTracks) {
           TextField(
               value = artistName,
               onValueChange = { artistName = it },
               label = { Text("Enter Artist Name") },
               modifier = Modifier.fillMaxWidth()
           )


           Button(
               onClick = {
                   if (artistName.isEmpty()) {
                       Toast.makeText(context, "Please enter an artist name", Toast.LENGTH_SHORT).show()
                   } else {
                       fetchTracks(artistName) { tracks ->
                           trackList = tracks
                       }
                   }
               },
               modifier = Modifier.fillMaxWidth()
           ) {
               Text("Fetch Tracks")
           }


           LazyColumn(
               modifier = Modifier.fillMaxWidth(),
               verticalArrangement = Arrangement.spacedBy(8.dp)
           ) {
               items(trackList) { track ->
                   Row(
                       modifier = Modifier.fillMaxWidth(),
                       horizontalArrangement = Arrangement.SpaceBetween,
                       verticalAlignment = Alignment.CenterVertically
                   ) {
                       Text(track, modifier = Modifier.weight(1f).padding(8.dp))
                       Button(
                           onClick = {
                               addTrackToFirebase(databaseReference, track) {
                                   addedTracks.add(track)
                               }
                           },
                           enabled = !addedTracks.contains(track)
                       ) {
                           Text(if (addedTracks.contains(track)) "Added" else "Add")
                       }
                   }
               }
           }


           // Button to view saved tracks
           Button(
               onClick = {
                   fetchSavedTracks(databaseReference) { tracks ->
                       savedTracks = tracks
                       showSavedTracks = true
                   }
               },
               modifier = Modifier.fillMaxWidth()
           ) {
               Text("View Saved Tracks")
           }
       } else {
           // Display saved tracks
           Text("Saved Tracks", style = MaterialTheme.typography.titleLarge)
           LazyColumn(
               modifier = Modifier.fillMaxWidth(),
               verticalArrangement = Arrangement.spacedBy(8.dp)
           ) {
               items(savedTracks) { (key, track) -> // Each item is a Pair(key, track)
                   Row(
                       modifier = Modifier.fillMaxWidth(),
                       horizontalArrangement = Arrangement.SpaceBetween,
                       verticalAlignment = Alignment.CenterVertically
                   ) {
                       Text(track, modifier = Modifier.weight(1f).padding(8.dp))
                       Button(onClick = {
                           removeTrackFromFirebase(databaseReference, key) // Remove track using its key
                           savedTracks = savedTracks.filterNot { it.first == key } // Update UI
                       }) {
                           Text("Remove")
                       }
                   }
               }
           }


           // Back to Search button
           Button(
               onClick = { showSavedTracks = false },
               modifier = Modifier.fillMaxWidth()
           ) {
               Text("Back to Search")
           }
       }
   }
}




// Fetch artist and track data from Spotify API
fun fetchTracks(artistName: String, onTracksFetched: (List<String>) -> Unit) {
   if (artistName.isEmpty()) {
       onTracksFetched(listOf("Please enter an artist name"))
       return
   }


   val client = OkHttpClient()
   val searchUrl = "https://api.spotify.com/v1/search?q=$artistName&type=artist&limit=1"


   val searchRequest = Request.Builder()
       .url(searchUrl)
       .addHeader("Authorization", "Bearer $ACCESS_TOKEN")
       .build()


   client.newCall(searchRequest).enqueue(object : Callback {
       override fun onFailure(call: Call, e: IOException) {
           Log.e("SpotifyAPI", "Failed to fetch artist", e)
           onTracksFetched(listOf("Failed to fetch artist: ${e.message}"))
       }


       override fun onResponse(call: Call, response: Response) {
           val responseData = response.body?.string()
           try {
               val jsonObject = JsonParser.parseString(responseData).asJsonObject
               val artists = jsonObject["artists"].asJsonObject["items"].asJsonArray


               if (artists.size() > 0) {
                   val artistId = artists[0].asJsonObject["id"].asString
                   fetchTopTracks(artistId, onTracksFetched)
               } else {
                   onTracksFetched(listOf("No artist found"))
               }
           } catch (e: Exception) {
               Log.e("SpotifyAPI", "Error parsing response", e)
               onTracksFetched(listOf("Error parsing response"))
           }
       }
   })
}


// Fetch top tracks using the artist ID
private fun fetchTopTracks(artistId: String, onTracksFetched: (List<String>) -> Unit) {
   val client = OkHttpClient()
   val topTracksUrl = "https://api.spotify.com/v1/artists/$artistId/top-tracks?market=US"


   val topTracksRequest = Request.Builder()
       .url(topTracksUrl)
       .addHeader("Authorization", "Bearer $ACCESS_TOKEN")
       .build()


   client.newCall(topTracksRequest).enqueue(object : Callback {
       override fun onFailure(call: Call, e: IOException) {
           Log.e("SpotifyAPI", "Failed to fetch tracks", e)
           onTracksFetched(listOf("Failed to fetch tracks: ${e.message}"))
       }


       override fun onResponse(call: Call, response: Response) {
           val responseData = response.body?.string()
           try {
               val jsonObject = JsonParser.parseString(responseData).asJsonObject
               val tracks = jsonObject["tracks"].asJsonArray


               val trackNames = tracks.map {
                   val trackName = it.asJsonObject["name"].asString
                   val artistName = it.asJsonObject["artists"].asJsonArray[0].asJsonObject["name"].asString
                   "$trackName by $artistName"
               }
               onTracksFetched(trackNames)
           } catch (e: Exception) {
               Log.e("SpotifyAPI", "Error parsing tracks", e)
               onTracksFetched(listOf("Error parsing tracks"))
           }
       }
   })
}


fun addTrackToFirebase(
   databaseReference: DatabaseReference,
   track: String,
   onComplete: () -> Unit
) {
   databaseReference.child("saved_tracks").push().setValue(track)
       .addOnSuccessListener {
           Log.d("Firebase", "Track added: $track")
           onComplete()
       }
       .addOnFailureListener { e ->
           Log.e("Firebase", "Failed to add track", e)
       }
}


fun removeTrackFromFirebase(databaseReference: DatabaseReference, key: String) {
   databaseReference.child("saved_tracks").child(key).removeValue()
       .addOnSuccessListener {
           Log.d("Firebase", "Track removed: $key")
       }
       .addOnFailureListener { e ->
           Log.e("Firebase", "Failed to remove track", e)
       }
}


fun fetchSavedTracks(databaseReference: DatabaseReference, onTracksFetched: (List<Pair<String, String>>) -> Unit) {
   databaseReference.child("saved_tracks").get().addOnSuccessListener { snapshot ->
       val tracks = snapshot.children.mapNotNull {
           val key = it.key
           val value = it.getValue(String::class.java)
           if (key != null && value != null) key to value else null
       }
       onTracksFetched(tracks)
   }.addOnFailureListener { e ->
       Log.e("Firebase", "Failed to fetch saved tracks", e)
       onTracksFetched(emptyList())
   }
}


