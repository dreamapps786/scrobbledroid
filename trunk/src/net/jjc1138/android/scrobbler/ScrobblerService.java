package net.jjc1138.android.scrobbler;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.util.EntityUtils;

import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.util.Log;

class InvalidMetadataException extends Exception {
	private static final long serialVersionUID = 1L;	
}
class IncompleteMetadataException extends InvalidMetadataException {
	private static final long serialVersionUID = 1L;
}

class Track {
	private final String sources = "PRELU";

	public Track(Intent i, Context c) throws InvalidMetadataException {
		String source = i.getStringExtra("source");
		if (source == null || source.length() < 1) {
			this.source = 'P';
		} else {
			this.source = source.charAt(0);
			if (sources.indexOf(this.source) == -1) {
				throw new InvalidMetadataException();
			}
		}
		
		id = i.getIntExtra("id", -1);
		
		if (id != -1) {
			final String[] columns = new String[] {
				MediaStore.Audio.AudioColumns.ARTIST,
				MediaStore.Audio.AudioColumns.TITLE,
				MediaStore.Audio.AudioColumns.DURATION,
				MediaStore.Audio.AudioColumns.ALBUM,
				MediaStore.Audio.AudioColumns.TRACK,
			};
			Cursor cur = c.getContentResolver().query(
				ContentUris.withAppendedId(
					MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
				columns, null, null, null);
			try {
				if (cur == null) {
					throw new NoSuchElementException();
				}
				cur.moveToFirst();
				// TODO Find out what happens when this stuff is absent from the
				// metadata DB.
				artist = cur.getString(cur.getColumnIndex(
					MediaStore.Audio.AudioColumns.ARTIST));
				track = cur.getString(cur.getColumnIndex(
					MediaStore.Audio.AudioColumns.TITLE));
				length = cur.getLong(cur.getColumnIndex(
					MediaStore.Audio.AudioColumns.DURATION));
				album = cur.getString(cur.getColumnIndex(
					MediaStore.Audio.AudioColumns.ALBUM));
				tracknumber = cur.getInt(cur.getColumnIndex(
					MediaStore.Audio.AudioColumns.TRACK));
			} finally {
				cur.close();
			}
		} else {
			// TODO Test this:
			
			// These are required:
			artist = i.getStringExtra("artist");
			if (artist == null || artist.length() == 0) {
				throw new IncompleteMetadataException();
			}
			track = i.getStringExtra("track");
			if (track == null || track.length() == 0) {
				throw new IncompleteMetadataException();
			}
			
			// This is required if source is P:
			length = new Long(i.getIntExtra("secs", -1));
			if (length == -1) {
				if (this.source == 'P') {
					throw new IncompleteMetadataException();
				} else {
					length = null;
				}
			} else {
				length *= 1000; // We store in milliseconds.
			}
			
			// These are optional:
			album = i.getStringExtra("album");
			if (album.length() == 0) {
				album = null;
			}
			tracknumber = i.getIntExtra("tracknumber", -1);
			if (tracknumber == -1) {
				tracknumber = null;
			}
			mbtrackid = i.getStringExtra("mb-trackid");
			if (mbtrackid.length() == 0) {
				mbtrackid = null;
			}
		}
	}

	public String getArtist() {
		return artist;
	}

	public String getTrack() {
		return track;
	}

	public char getSource() {
		return source;
	}

	public Long getMillis() {
		return length;
	}

	public Long getSecs() {
		return length == null ? null : length / 1000;
	}

	public String getAlbum() {
		return album;
	}

	public Integer getTracknumber() {
		return tracknumber;
	}

	public String getMbtrackid() {
		return mbtrackid;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Track)) {
			return false;
		}
		Track other = (Track) o;
		if (id != -1) {
			return id == other.id;
		}
		return
			artist == other.artist &&
			track == other.track &&
			source == other.source &&
			length == other.length &&
			album == other.album &&
			tracknumber == other.tracknumber &&
			mbtrackid == other.mbtrackid;
	}

	@Override
	public int hashCode() {
		return id;
	}

	@Override
	public String toString() {
		String s = (id == -1) ? "" : "(" + id + ") ";
		return s + artist + " - " + track;
	}

	private int id;

	private String artist;
	private String track;
	private char source;
	private Long length;
	private String album;
	private Integer tracknumber;
	private String mbtrackid;
}

class QueueEntry {
	public QueueEntry(Track track, long startTime) {
		this.track = track;
		this.startTime = startTime;
	}

	public Track getTrack() {
		return track;
	}

	public long getStartTime() {
		return startTime;
	}

	private Track track;
	private long startTime;
}

class Session {
	Session(String id, String submissionURL) {
		this.id = id;
		this.submissionURL = submissionURL;
		this.valid = true;
	}

	public String getId() {
		return id;
	}

	public String getSubmissionURL() {
		return submissionURL;
	}

	public void invalidate() {
		valid = false;
	}

	public boolean isValid() {
		return valid;
	}

	private String id;
	private String submissionURL;
	private boolean valid;
}

public class ScrobblerService extends Service {
	// This is the maximum number of tracks that we can submit in one request:
	static final int MAX_SCROBBLE_TRACKS = 50;
	static final long INITIAL_HANDSHAKE_RETRY_WAITING_TIME = 60000;

	// This is the number of tracks that we will wait to have queued before we
	// scrobble. It can be larger or smaller than MAX_SCROBBLE_TRACKS.
	static final int SCROBBLE_BATCH_SIZE = MAX_SCROBBLE_TRACKS;
	// This is how long we will wait after music has stopped playing before
	// scrobbling.
	static final int SCROBBLE_WAITING_TIME_MINUTES = 3;

	static final String LOG_TAG = "ScrobbleDroid";
	static final String PREFS = "prefs";

	static final int OK = 0;
	static final int NOT_YET_ATTEMPTED = 1;
	static final int BANNED = 2;
	static final int BADAUTH = 3;
	static final int BADTIME = 4;
	static final int FAILED_NET = 5;
	static final int FAILED_OTHER = 6;

	final RemoteCallbackList<IScrobblerServiceNotificationHandler>
		notificationHandlers =
		new RemoteCallbackList<IScrobblerServiceNotificationHandler>();
	SharedPreferences prefs;

	private int lastScrobbleResult = NOT_YET_ATTEMPTED;
	private BlockingQueue<QueueEntry> queue =
		new LinkedBlockingQueue<QueueEntry>();

	private QueueEntry lastPlaying = null;
	private boolean lastPlayingWasPaused = true;
	private long lastPlayingTimePlayed = 0;
	private long lastResumedTime = -1;

	private ScrobbleThread scrobbleThread = null;
	private Handler handler;
	private boolean bound = false;
	// This is the time of the last "meaningful" event, i.e. a track that was
	// playing being paused, or vice-versa, or a new track being played.
	private long lastEventTime = -1;

	// This musn't be reassigned unless the caller holds the handshaking lock as
	// described below. It is acceptable to invalidate() the Session without
	// holding the lock.
	private Session session;
	// It would be bad if two threads were handshaking at the same time so
	// anyone who wants to handshake must synchronize on the following object.
	// After that lock is obtained, they must then check to see if the above
	// Session isValid(). If so they should take their own copy of the reference
	// to it, and then release the lock and attempt to use that Session. If it
	// wasn't valid then they should handshake, store the new Session in the
	// above variable and then release the lock.
	private Object handshaking = new Object();

	private int hardFailures = 0;
	private long handshakeRetryWaitingTime =
		INITIAL_HANDSHAKE_RETRY_WAITING_TIME;

	@Override
	public void onCreate() {
		super.onCreate();
		prefs = getSharedPreferences(PREFS, 0);
		handler = new Handler();
		
		session = new Session("", "");
		session.invalidate();
		// TODO Load saved queue if there is one and then delete the file.
		// TODO Load saved lastPlaying if there is one and then delete the file.
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		// TODO Save queue if there is one.
	}

	private boolean isScrobbling() {
		return scrobbleThread != null && scrobbleThread.inProgress();
	}

	synchronized void updateAllClients() {
		final int N = notificationHandlers.beginBroadcast();
		for (int i = 0; i < N; ++i) {
			updateClient(notificationHandlers.getBroadcastItem(i));
		}
		notificationHandlers.finishBroadcast();
	}

	synchronized void updateClient(IScrobblerServiceNotificationHandler h) {
		try {
			// As far as I can tell this doesn't have to be called from our main
			// event thread.
			h.stateChanged(queue.size(), isScrobbling(), lastScrobbleResult);
		} catch (RemoteException e) {}
	}

	private final IScrobblerService.Stub binder = new IScrobblerService.Stub() {

		@Override
		public void registerNotificationHandler(
			IScrobblerServiceNotificationHandler h) throws RemoteException {
			
			notificationHandlers.register(h);
			updateClient(h);
		}

		@Override
		public void unregisterNotificationHandler(
			IScrobblerServiceNotificationHandler h) throws RemoteException {
			
			notificationHandlers.unregister(h);
			handler.post(new Runnable() {
				@Override
				public void run() {
					stopIfIdle();
				}
			});
		}

		@Override
		public void prefsUpdated() throws RemoteException {
			// Force a rehandshake on the next scrobble:
			session.invalidate();
			
			if (lastScrobbleResult == BADAUTH) {
				lastScrobbleResult = NOT_YET_ATTEMPTED;
				updateAllClients();
			}
			
			// TODO Remove this method from the interface and instead use
			// SharedPreferences.registerOnSharedPreferenceChangeListener().
		}

		@Override
		public void startScrobble() throws RemoteException {
			scrobbleNow();
		}

	};

	@Override
	public IBinder onBind(Intent intent) {
		bound = true;
		return binder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		bound = false;
		return false;
	}

	private void newTrackStarted(Track t, long now) {
		this.lastPlaying = new QueueEntry(t, now);
		lastPlayingWasPaused = false;
		lastPlayingTimePlayed = 0;
		lastResumedTime = now;
		lastEventTime = now;
		Log.v(LOG_TAG, "New track started.");
	}

	private boolean playedWholeTrack() {
		// Put some wiggle room in to compensate for the imprecision of our
		// timekeeping.
		long v =
			lastPlayingTimePlayed - lastPlaying.getTrack().getMillis();
		long a = Math.abs(v);
		if (a < 30000) {
			Log.d(LOG_TAG, "Whole track timing error: " + v);
		}
		return a < 3000;
	}

	private boolean playTimeEnoughForScrobble() {
		final long playTime = lastPlayingTimePlayed;
		// For debugging:
		//return playTime >= 5000;
		if (playTime < 30000) {
			return false;
		}
		if (playTime >= 240000) {
			return true;
		}
		Long length = lastPlaying.getTrack().getMillis();
		if (length == null) {
			length = 1000L;
		}
		return playTime >= (length / 2);
	}

	private void handleIntent(Intent intent) {
		Log.v(LOG_TAG, "Status: " +
			((intent.getBooleanExtra("playing", false) ? "playing" : "stopped")
			+ " track " + intent.getIntExtra("id", -1)));
		
		if (!prefs.getBoolean("enable", true)) {
			return;
		}
		if (!intent.hasExtra("playing")) {
			// That one is mandatory.
			return;
		}
		Track t;
		try {
			t = new Track(intent, this);
		} catch (InvalidMetadataException e) {
			return;
		} catch (NoSuchElementException e) {
			return;
		}
		long now = System.currentTimeMillis();
		
		if (intent.getBooleanExtra("playing", false)) {
			if (lastPlaying == null) {
				newTrackStarted(t, now);
			} else {
				if (lastPlaying.getTrack().equals(t)) {
					if (lastPlayingWasPaused) {
						lastResumedTime = now;
						lastEventTime = now;
						lastPlayingWasPaused = false;
						Log.v(LOG_TAG, "Previously paused track resumed.");
					} else {
						if (playedWholeTrack() && playTimeEnoughForScrobble()) {
							queue.add(lastPlaying);
							newTrackStarted(t, now);
							Log.v(LOG_TAG, "Enqueued repeating track.");
							updatedQueue();
						} else {
							// lastPlaying track is still playing, but hasn't
							// gotten to the end yet (and so isn't repeating):
							// NOOP.
						}
					}
				} else {
					if (!lastPlayingWasPaused) {
						lastPlayingTimePlayed += now - lastResumedTime;
					}
					final String logState = lastPlayingWasPaused ?
						"paused" : "playing";
					if (playTimeEnoughForScrobble()) {
						queue.add(lastPlaying);
						Log.v(LOG_TAG,
							"Enqueued previously " + logState + " track.");
						updatedQueue();
					} else {
						Log.v(LOG_TAG, "Previously " + logState +
							" track wasn't playing long enough to scrobble.");
					}
					newTrackStarted(t, now);
				}
			}
		} else {
			// Paused/stopped.
			if (lastPlaying == null || lastPlayingWasPaused) {
				// We weren't playing before and we aren't playing now: NOOP.
			} else {
				// A track was playing.
				lastPlayingTimePlayed += now - lastResumedTime;
				lastPlayingWasPaused = true;
				lastEventTime = now;
				Log.v(LOG_TAG, "Track paused/stopped. Total play time so far " +
					"is " + lastPlayingTimePlayed + ".");
				if (playedWholeTrack() && playTimeEnoughForScrobble()) {
					queue.add(lastPlaying);
					lastPlaying = null;
					lastPlayingTimePlayed = 0;
					Log.v(LOG_TAG, "Enqueued paused/stopped track.");
					updatedQueue();
				} else {
					// If the whole track wasn't played then that's okay: the
					// track will still be queued eventually by stopIfIdle()
					// below if it has played for long enough.
					//
					// We queue completed tracks now to make the UI more
					// intuitive. If a playlist ends then the last track will be
					// queued immediately.
					//
					// If we wanted to we could queue any track that has played
					// for long enough at this point. The reason we don't do
					// that is to avoid enqueuing duplicates when a track wasn't
					// really repeated. If we did enqueue partially played
					// tracks now then the following situation could occur:
					// 1) A track plays for 55% of it's play time.
					// 2) User pauses. The track gets enqueued and
					//    lastPlayingTimePlayed is reset to zero.
					// 3) User rewinds it back 10% so it is at 45%. We don't get
					//    informed about this type of event.
					// 4) User resumes.
					// 5) Track plays until the end. 55% of it has played so we
					//    assume that it was repeated. It gets enqueued again.
					// In fact the user had only listened to 110% of the track
					// so they probably don't want to scrobble it twice.
					//
					// Unfortunately the situation described above can still
					// occur if the user has the immediate scrobbling mode
					// switched on, but there doesn't seem to be an obvious way
					// of avoiding it in that case.
				}
			}
		}
		
		// TODO Make sure we're sensibly handling (maliciously) malformed
		// Intents.
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		
		handleIntent(intent);
		stopIfIdle();
	}

	private void stopIfIdle() {
		if (!lastPlayingWasPaused) {
			return;
		}
		if (isScrobbling()) {
			return;
		}
		
		if (!prefs.getBoolean("immediate", false)) {
			final long waitingTimeMillis =
				SCROBBLE_WAITING_TIME_MINUTES * 60 * 1000;
			if (System.currentTimeMillis() - lastEventTime <
				waitingTimeMillis) {
				
				// Check again later, because the user might start playing music
				// again soon.
				handler.postDelayed(new Runnable() {
					@Override
					public void run() {
						stopIfIdle();
					}
				}, waitingTimeMillis);
				return;
			}
		}
		
		// Check if the paused/stopped track should be scrobbled.
		if (lastPlaying != null && playTimeEnoughForScrobble()) {
			queue.add(lastPlaying);
			lastPlaying = null;
			lastPlayingTimePlayed = 0;
			Log.v(LOG_TAG, "Enqueued previously paused/stopped track.");
			updatedQueue();
		}
		if (!queue.isEmpty()) {
			scrobbleNow();
			return; // When the scrobble ends this method will be called again.
		}
		
		if (bound) {
			return;
		}
		
		// Not playing, queue empty, not scrobbling, and no clients connected:
		// it looks like we really are idle!
		
		// TODO Save the lastPlaying information (including
		// lastPlayingTimePlayed) and then stopSelf().
	}

	private boolean shouldScrobbleNow() {
		final int queueSize = queue.size();
		if (queueSize == 0) {
			return false;
		}
		if (prefs.getBoolean("immediate", false)) {
			return true;
		}
		if (queueSize >= SCROBBLE_BATCH_SIZE) {
			return true;
		}
		return false;
	}

	private void updatedQueue() {
		if (shouldScrobbleNow()) {
			scrobbleNow();
		}
		updateAllClients();
	}

	private static String toHexString(byte[] bytes) {
		StringBuffer sb =
			new StringBuffer(bytes.length * 2);
		for (byte b : bytes) {
			// Avoid sign extension:
			int i = ((int)b) & 0x000000ff;
			String h = Integer.toHexString(i);
			if (h.length() == 1) {
				h = '0' + h;
			}
			sb.append(h);
		}
		assert sb.length() == bytes.length * 2;
		return sb.toString();
	}

	private class ScrobbleThread extends Thread {
		final static String handshakeURL =
			"http://post.audioscrobbler.com:80/";
		final static String apiVersion = "1.2.1";
		final static String encoding = "UTF-8";

		final static String clientID = "tst";

		private boolean inProgress = true;
		private Session s = session;
		private HttpClient client;

		ScrobbleThread() {
			this.setName("ScrobbleThread");
			
			HttpParams p = new BasicHttpParams();
			HttpProtocolParams.setVersion(p, HttpVersion.HTTP_1_1);
			client = new DefaultHttpClient(p);
		}

		private class HardFailure extends IOException {
			private static final long serialVersionUID = 1L;
		}

		private class UserFailure extends Exception {
			private static final long serialVersionUID = 1L;

			public UserFailure(int reason) {
				this.reason = reason;
			}

			public int getReason() {
				return reason;
			}

			private int reason;
		}

		private String enc(String s) {
			try {
				return URLEncoder.encode(s, encoding);
			} catch (UnsupportedEncodingException e) {
				assert false;
				return null;
			}
		}

		private void handshake() throws IOException, HardFailure, UserFailure {
			if (s.isValid()) {
				Log.v(LOG_TAG, "Session appears to valid already.");
				return;
			}
			synchronized (handshaking) {
				if (session.isValid()) {
					s = session;
					return;
				}
				URI u = null;
				try {
					String timestamp = Long.toString(
						System.currentTimeMillis() / 1000);
					MessageDigest md5 = MessageDigest.getInstance("MD5");
					byte[] passwordMD5 = md5.digest(
						prefs.getString("password", null).getBytes(encoding));
					md5.reset();
					String token = toHexString(md5.digest(
						(toHexString(passwordMD5) + timestamp).getBytes(
							encoding)));
					u = new URI(
						handshakeURL + '?' +
						"hs=true&" +
						"p=" + enc(apiVersion) + '&' +
						"c=" + enc(clientID) + '&' +
						// The "tst" clientID has to use version "1.0":
						"v=" + enc(clientID.equals("tst") ? "1.0" :
							getString(R.string.app_version)) + '&' +
						"u=" + enc(prefs.getString("username", null)) + '&' +
						"t=" + enc(timestamp) + '&' +
						"a=" + enc(token));
				} catch (NoSuchAlgorithmException e) {
					assert false;
				} catch (UnsupportedEncodingException e) {
					assert false;
				} catch (URISyntaxException e) {
					assert false;
				}
				
				HttpResponse r = client.execute(new HttpGet(u));
				if (r.getStatusLine().getStatusCode() != 200) {
					throw new HardFailure();
				}
				HttpEntity e = r.getEntity();
				String resp;
				try {
					resp = EntityUtils.toString(e, "US-ASCII");
				} finally {
					e.consumeContent();
				}
				String[] lines = resp.split("\n");
				if (lines.length < 1) {
					throw new HardFailure();
				}
				if (lines[0].equals("OK")) {
					// Phew.
				} else if (lines[0].equals("BANNED")) {
					throw new UserFailure(BANNED);
				} else if (lines[0].equals("BADAUTH")) {
					throw new UserFailure(BADAUTH);
				} else if (lines[0].equals("BADTIME")) {
					throw new UserFailure(BADTIME);
				} else if (lines[0].startsWith("FAILED")) {
					throw new HardFailure();
				} else {
					throw new HardFailure();
				}
				if (lines.length < 4) {
					throw new HardFailure();
				}
				s = session = new Session(lines[1], lines[3]);
				
				hardFailures = 0;
				handshakeRetryWaitingTime =
					INITIAL_HANDSHAKE_RETRY_WAITING_TIME;
				
				Log.v(LOG_TAG, "New session started.");
			}
		}

		@Override
		public void run() {
			if (lastScrobbleResult == BANNED || lastScrobbleResult == BADAUTH) {
				// TODOLATER According to the spec. we should also refuse to
				// handshake after a BADTIME response until the clock has been
				// changed, but we'll need to monitor for time changes to
				// implement that.
				inProgress = false;
				updateAllClients();
				return;
			}
			try {
				handshake();
				
				QueueEntry e;
				while ((e = queue.peek()) != null) {
					updateAllClients(); // Update the number of tracks left.
					// TODO Make real:
					try {
						sleep(2500);
					} catch (InterruptedException e1) {}
					Log.v(LOG_TAG, "(Pretend) Scrobbling track: " +
						e.getTrack().toString());
					queue.remove();
				}
				
				inProgress = false;
				lastScrobbleResult = OK;
			} catch (IOException e) {
				Runnable retry = new Runnable() {
					@Override
					public void run() {
						scrobbleNow();
					}
				};
				boolean failureDuringScrobbling = session.isValid();
				if (failureDuringScrobbling) {
					++hardFailures;
					Log.v(LOG_TAG, hardFailures + " hard failures so far.");
					if (hardFailures > 2) {
						session.invalidate();
					}
				}
				inProgress = false;
				lastScrobbleResult = e instanceof HardFailure ?
					FAILED_OTHER : FAILED_NET;
				if (failureDuringScrobbling) {
					handler.post(retry);
				} else {
					final long current = handshakeRetryWaitingTime;
					handshakeRetryWaitingTime *= 2;
					final long twoHours = 2 * 60 * 60 * 1000;
					if (handshakeRetryWaitingTime > twoHours) {
						handshakeRetryWaitingTime = twoHours;
					}
					Log.v(LOG_TAG, "Waiting " + (current / 60 / 1000) +
						" minute(s) before retrying handshake.");
					handler.postDelayed(retry, current);
				}
			} catch (UserFailure e) {
				inProgress = false;
				lastScrobbleResult = e.getReason();
			}
			
			updateAllClients();
			
			handler.post(new Runnable() {
				@Override
				public void run() {
					stopIfIdle();
				}
			});
		}

		public boolean inProgress() {
			// You might think that we could just use Thread.isAlive() instead
			// of having this method. We use this method so that when we are
			// finished we can call updateAllClients() from this thread and have
			// it report that we have finished scrobbling even though the thread
			// hasn't actually terminated yet.
			return inProgress;
		}
	}

	synchronized private void scrobbleNow() {
		if (!isScrobbling()) {
			scrobbleThread = new ScrobbleThread();
			scrobbleThread.start();
			updateAllClients();
		}
	}

}