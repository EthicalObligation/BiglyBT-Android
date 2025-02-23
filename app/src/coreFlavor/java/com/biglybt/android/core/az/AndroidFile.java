/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.biglybt.android.core.az;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import androidx.annotation.*;
import androidx.documentfile.provider.DocumentFile;

import com.biglybt.android.client.AndroidUtils;
import com.biglybt.android.client.BiglyBTApp;
import com.biglybt.android.util.PathInfo;
import com.biglybt.core.util.*;
import com.biglybt.util.AssumeNoSideEffects;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * @implNote DocumentFile doesn't like parent stuff.
 * <p/>
 * Base content URI:<br/>
 * <code>"content://" + HANDLER_ID + "/tree/" + BASE_LOCATION</code>
 * <br/>
 * Base content URI is what you receive from picker and request permissions on.
 * Can't walk up the tree from base. Any files and folders created within have a URI of<br/>
 * <code>"content://" + HANDLER_ID + "/tree/" + BASE_LOCATION + "/document/" + BASE_LOCATION + (optional) SUBLOCATION</code>
 * <br/>
 * where SUBLOCATION is something like "%2Fsubpath%2Fsomefile"
 * <p>
 * <hr/>
 * If we retrieve the real File using {@link com.biglybt.android.util.PaulBurkeFileUtils},
 * we could speed up quite a few methods ({@link #exists()}, {@link #length()}, etc)
 * <hr/>
 * <code>docFile</code> and <code>uri</code> is built when needed. Reasoning:
 * A lot of new instances are created only to call {@link #getName()} or {@link #getAbsolutePath()}.
 */
@SuppressWarnings("MethodDoesntCallSuperMethod")
@Keep
@RequiresApi(api = VERSION_CODES.LOLLIPOP)
public class AndroidFile
	extends File
{
	private static long uniqueNumber = 0;

	private static final String TAG = "AndroidFile";

	private static final boolean DEBUG_CALLS = AndroidUtils.DEBUG;

	@SuppressWarnings("ConstantConditions")
	private static final boolean DEBUG_CALLS_SPAM = AndroidUtils.DEBUG && false;

	private boolean needsBuilding = true;

	@NonNull
	String path;

	private DocumentFile docFile;

	private Uri uri;

	AndroidFile parentFile;

	private PathInfo pathInfo;

	AndroidFile(@NonNull String path) {
		super(Long.toString(uniqueNumber++));
		this.path = path;
		if (DEBUG_CALLS && !path.contains("/document/")) {
			logw("no /document/ in " + path);
		}

		log("new");
	}

	AndroidFile(@NonNull DocumentFile documentFile, @NonNull Uri uri,
			@NonNull String path) {
		super(Long.toString(uniqueNumber++));
		this.uri = uri;
		this.path = path;
		docFile = documentFile;
		log("new(docFile)");
	}

	static String fixDirName(@NonNull String dir) {
		// All strings will start with "content://", but caller might have blindly
		// appended a File.separator.  
		// ie.  FileUtil.newFile(someFile.toString + File.separator + "foo")
		// which would result in something like
		// content://provider.id/tree/5555-5555%3Afolder/foo
		// TODO: Need to at least check for this case and warn dev
		// slashes after "folder" ARE valid, such as folder/children, folder/document, etc
		// so we can't blindly say a slash after "tree/" is invalid
		int documentPos = dir.indexOf("/document/");
		int lastRealSlash = dir.lastIndexOf('/');
		if (lastRealSlash > documentPos) {
			int firstEncodedSlash = dir.indexOf("%2F",
					documentPos > 0 ? documentPos + 10 : 0);
			if (firstEncodedSlash > 0) {
				int firstSlash = dir.indexOf('/', firstEncodedSlash + 1);
				if (firstSlash > 0) {
					Log.e(TAG, "fixDirName] dir has File.separatorChar! " + dir + "; "
							+ AndroidUtils.getCompressedStackTrace());
				}
			}
		}
		return dir;
	}

	@NonNull
	@Override
	public String getName() {
		// docFile.getName() queries COLUMN_DISPLAY_NAME.  Seems excessive when 
		// parsing the document id works.  Plus, Display name could be different
		// than the file name.
		if (uri == null) {
			build(true);
		}
		String documentId = DocumentsContract.getDocumentId(uri);

		if (documentId != null) {
			int slashPos = documentId.lastIndexOf(':');
			int colonPos = documentId.lastIndexOf('/');
			if (slashPos >= 0 || colonPos >= 0) {
				String name = documentId.substring(Math.max(slashPos, colonPos) + 1);
				if (DEBUG_CALLS_SPAM) {
					log("getname(parsed)=" + name);
				}
				return name;
			}
		}

		String name = getDocFile().getName();
		log("getname=" + name);
		return name == null ? "" : name;
	}

	@Nullable
	@Override
	public String getParent() {
		AndroidFile parentFile = getParentFile();
		if (parentFile == null) {
			return null;
		}
		log("getParent=" + parentFile.path);
		return parentFile.path;
	}

	@Nullable
	@Override
	public AndroidFile getParentFile() {
		if (parentFile != null) {
			return parentFile;
		}

		DocumentFile parentDocFile = getDocFile().getParentFile();
		if (parentDocFile == null) {
			try {
				// getDocumentId will return a decoded docPath (%2F -> /)
				String docPath = DocumentsContract.getDocumentId(getUri());

				int i = docPath.lastIndexOf('/');
				if (i > 0) {
					parentDocFile = DocumentFile.fromTreeUri(BiglyBTApp.getContext(),
							DocumentsContract.buildDocumentUriUsingTree(getUri(),
									docPath.substring(0, i)));
				} else {
					log("getParentFile: docPath has no /");
					return null;
				}
			} catch (IllegalArgumentException e) {
				log("Can't get parent of " + path + ". " + e.toString());
				return null;
			}
		}

		log("getParentFile="
				+ (parentDocFile == null ? null : parentDocFile.getUri().toString()));
		if (parentDocFile != null) {
			parentFile = AndroidFileHandler.newFile(parentDocFile);
		}
		return parentFile;
	}

	@NonNull
	@Override
	public String getPath() {
		log("getPath");
		return path;
	}

	@Override
	public boolean isAbsolute() {
		log("isAbsolute");
		return true;
	}

	@NonNull
	@Override
	public String getAbsolutePath() {
		if (DEBUG_CALLS_SPAM) {
			log("getAbsolutePath");
		}
		return path;
	}

	@NonNull
	@Override
	public File getAbsoluteFile() {
		if (DEBUG_CALLS_SPAM) {
			log("getAbsoluteFile");
		}
		return this;
	}

	@NonNull
	@Override
	public String getCanonicalPath()
			throws IOException {
		if (DEBUG_CALLS_SPAM) {
			log("getCanonicalPath");
		}
		if (needsBuilding) {
			build(false);
		}
		return path;
	}

	@NonNull
	@Override
	public File getCanonicalFile()
			throws IOException {
		if (DEBUG_CALLS_SPAM) {
			log("getCanonicalFile");
		}
		return this;
	}

	@SuppressWarnings("deprecation")
	@NonNull
	@Override
	public URL toURL()
			throws MalformedURLException {
		log("toURL");
		return new URL(path);
	}

	@NonNull
	@Override
	public URI toURI() {
		log("toURI");
		return URI.create(path);
	}

	@Override
	public boolean canRead() {
		boolean canRead = getDocFile().canRead();
		log("canRead=" + canRead);
		return canRead;
	}

	@Override
	public boolean canWrite() {
		boolean canWrite = getDocFile().canWrite();
		if (canWrite && isDirectory()) {
			try {
				DocumentFile file = getDocFile().createFile("application/octet-stream",
						System.currentTimeMillis() + ".tmp");
				if (file == null) {
					log("canWrite=forced false");
					return false;
				}
				file.delete();
			} catch (Throwable t) {
				log("canWrite=false(" + t.toString() + ")");
				return false;
			}
		}
		log("canWrite=" + canWrite);
		return canWrite;
	}

	@Override
	public boolean exists() {
		boolean exists;
		try {
			// Found in wild:
			// Crash | Google Intel Apollo Lake Chromebook  | Android 9 (SDK 28)
			// signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)
			//  androidx.core.app.AppOpsManagerCompat.closeQuietly [DEDUPED]+48)
			//  androidx.documentfile.provider.TreeDocumentFile.exists+448)
			//  com.biglybt.android.core.az.AndroidFile.exists+152)
			//  com.biglybt.android.core.az.AndroidFile.mkdirs+42)
			//  com.biglybt.android.core.az.BiglyBTManager.<init>+10232)
			//  com.biglybt.android.client.service.BiglyBTService.startCore+644)
			//  com.biglybt.android.client.service.c.run+1036)
			exists = getDocFile().exists();
		} catch (Throwable t) {
			return false;
		}
		if (exists && path.contains("/raw%3A")) {
			// Not always valid. For example:
			// content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Ffoobar/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Ffoobar%2Fsamples
			// DocumentId = raw:/storage/emulated/0/Download/foobar/samples
			// TreeDocumentId = raw:/storage/emulated/0/Download/foobar
			// Assuming "foobar" is an existing directory, exists() on any document
			// under "foobar" results in true.
			// However, the length, modified date, etc are 0 for ones that don't actually exist
			// I'm not sure if this is the case for just content URIs that aren't "raw:", or all content URIs
			exists = lastModified() > 0;
		}
		log("exists=" + exists);
		return exists;
	}

	@Override
	public boolean isDirectory() {
		boolean directory = getDocFile().isDirectory();
		log("isDir=" + directory);
		return directory;
	}

	@Override
	public boolean isFile() {
		boolean isFile = getDocFile().isFile();
		log("isFile=" + isFile);
		return isFile;
	}

	@Override
	public boolean isHidden() {
		log("isHidden");
		return false;
	}

	@Override
	public long lastModified() {
		// Note: DocumentFile#lastModified() returns 0 on special folders, such as the "Downloads" folder:
		//   content://com.android.providers.downloads.documents/tree/downloads
		// our getDocFile() will convert the path to a real one:
		//   content://com.android.providers.downloads.documents/tree/downloads/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload
		// which does return a valid lastModified
		long lastModified = getDocFile().lastModified();
		log("lastModified=" + lastModified);
		return lastModified;
	}

	@Override
	public long length() {
		long length = getDocFile().length();
		log("length=" + length);
		return length;
	}

	@Override
	public boolean createNewFile()
			throws IOException {
		log("createNewFile");
		if (exists()) {
			return false;
		}
		AndroidFile parentFile = getParentFile();
		if (parentFile == null) {
			return false;
		}

		log("createNewFile in " + parentFile);
		DocumentFile file = parentFile.getDocFile().createFile(
				"application/octet-stream", getName());
		if (file != null) {
			docFile = file;
			uri = getDocFile().getUri();
		}
		return file != null;
	}

	@Override
	public boolean delete() {
		// Checking exists() takes time, but docFile.delete will spew crap to
		// logcat when file doesn't exists, and I'd rather avoid that.
		if (!exists()) {
			log("delete(!exist)=false");
			return false;
		}
		boolean deleted;
		try {
			deleted = getDocFile().delete();
			log("delete=" + deleted);
		} catch (IllegalArgumentException e) {
			// FNF Exception usually wrapped in IllegalArgumentException
			deleted = false;
			log("delete(" + e.getMessage() + ")=false");
		}
		return deleted;
	}

	@Override
	public void deleteOnExit() {
		logw("deleteOnExit");

		// TODO
		//super.deleteOnExit();
	}

	@Nullable
	@Override
	public String[] list() {
		log("list");
		if (!getDocFile().canRead()) {
			Log.w(TAG, "list: can't read " + path);
			return new String[0];
		}
		DocumentFile[] files = getDocFile().listFiles();
		String[] fileStrings = new String[files.length];
		for (int i = 0, filesLength = files.length; i < filesLength; i++) {
			fileStrings[i] = files[i].getName();
		}

		return fileStrings;
	}

	@Nullable
	@Override
	public String[] list(@Nullable FilenameFilter filter) {
		log("list");

		if (filter == null) {
			return list();
		}
		DocumentFile[] files = getDocFile().listFiles();
		List<String> list = new ArrayList<>();
		for (DocumentFile docFile : files) {
			AndroidFile f = AndroidFileHandler.newFile(docFile);
			String name = docFile.getName();
			if (filter.accept(f, name)) {
				list.add(name);
			}
		}

		return list.toArray(new String[0]);
	}

	@Nullable
	@Override
	public File[] listFiles() {

		DocumentFile[] files = getDocFile().listFiles();
		log("listFiles(" + files.length + ")");
		File[] javaFiles = new File[files.length];
		for (int i = 0, filesLength = files.length; i < filesLength; i++) {
			javaFiles[i] = AndroidFileHandler.newFile(files[i]);
		}

		return javaFiles;
	}

	@Nullable
	@Override
	public File[] listFiles(@Nullable FilenameFilter filter) {
		if (filter == null) {
			return listFiles();
		}
		DocumentFile[] files = getDocFile().listFiles();
		log("listFiles(" + files.length + ")");
		List<File> list = new ArrayList<>();
		for (DocumentFile docFile : files) {
			AndroidFile f = AndroidFileHandler.newFile(docFile);
			if (filter.accept(f, docFile.getName())) {
				list.add(f);
			}
		}

		return list.toArray(new File[0]);
	}

	@Nullable
	@Override
	public File[] listFiles(@Nullable FileFilter filter) {
		if (filter == null) {
			return listFiles();
		}
		DocumentFile[] files = getDocFile().listFiles();
		log("listFiles(" + files.length + ")");
		List<File> list = new ArrayList<>();
		for (DocumentFile file : files) {
			AndroidFile f = AndroidFileHandler.newFile(file);
			if (filter.accept(f)) {
				list.add(f);
			}
		}

		return list.toArray(new File[0]);
	}

	@Override
	public boolean mkdir() {
		if (exists()) {
			log("mkdir(exists)");
			return true;
		}
		AndroidFile parentFile = getParentFile();
		if (parentFile == null) {
			logw("mkdir(noparent)");
			return false;
		}
		DocumentFile directory = parentFile.getDocFile().createDirectory(getName());
		log("mkdir=" + directory);
		return directory != null;
	}

	@Override
	public boolean mkdirs() {
		if (exists()) {
			log("mkdirs(exist)");
			return false;
		}
		if (mkdir()) {
			log("mkdirs=true");
			return true;
		}
		File parent = getParentFile();
		if (parent == null) {
			log("mkdirs(noparent)");
			return false;
		}
		boolean ok = (parent.mkdirs() || parent.exists()) && mkdir();
		log("mkdirs=" + ok);
		return ok;
	}

	/**
	 * Copied from DocumentsContractApi19
	 */
	private static long queryForLong(Context context, Uri self, String column,
			long defaultValue) {
		final ContentResolver resolver = context.getContentResolver();

		Cursor c = null;
		try {
			c = resolver.query(self, new String[] {
				column
			}, null, null, null);
			if (c != null && c.moveToFirst() && !c.isNull(0)) {
				return c.getLong(0);
			} else {
				return defaultValue;
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed query: " + e);
			return defaultValue;
		} finally {
			closeQuietly(c);
		}
	}

	private static void closeQuietly(@Nullable AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (RuntimeException rethrown) {
				throw rethrown;
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	public boolean renameTo(@NonNull File dest) {
		if (!(dest instanceof AndroidFile)) {
			Log.e(TAG, "renameTo: dest not AndroidFile "
					+ AndroidUtils.getCompressedStackTrace());
			return false;
		}

		if (path.contains("/raw%3A")
				|| ((AndroidFile) dest).path.contains("/raw%3A")) {
			// moving from a 'raw:' content uri to a non-'raw:' will throw IllegalArgumentException in DocumentsContract.moveDocument
			// moving from a non-'raw:' to 'raw:' throws android.os.ParcelableException: java.io.FileNotFoundException: No root for raw
			return copyAndDelete(this, dest);
		}

		log("renameTo: " + dest + " from " + this);

		boolean sameName = getName().equals(dest.getName());

		if (!sameName && getParent().equals(dest.getParent())
				&& getDocFile().renameTo(dest.getName())) {
			uri = docFile.getUri();
			path = uri.toString();
			needsBuilding = true;
			return true;
		}

		if (VERSION.SDK_INT >= VERSION_CODES.N && sameName) {
			AndroidFile parentDir = getParentFile();
			AndroidFile destParent = (AndroidFile) dest.getParentFile();

			if (parentDir != null && destParent != null) {
				Uri parentUri = parentDir.getUri();
				long flagsParent = queryForLong(BiglyBTApp.getContext(),
						parentDir.getUri(), Document.COLUMN_FLAGS, 0);
				long flagsFile = queryForLong(BiglyBTApp.getContext(), getUri(),
						Document.COLUMN_FLAGS, 0);
				if ((flagsParent & Document.FLAG_DIR_SUPPORTS_CREATE) > 0
						&& (flagsFile & Document.FLAG_SUPPORTS_MOVE) > 0) {
					try {
						log("renameTo: using moveDocument");
						// non-'raw:" internal to non-'raw:' external tested and works
						Uri movedDoc = DocumentsContract.moveDocument(
								BiglyBTApp.getContext().getContentResolver(), this.getUri(),
								parentUri, destParent.getUri());
						if (movedDoc != null) {
							return true;
						}
					} catch (Exception e) {
						Log.e(TAG, "renameTo", e);
					}
				}
			}
		}

		return copyAndDelete(this, dest);
	}

	/**
	 * Copies a file via FileChannel and deletes original on success
	 *
	 * @implNote Copied from com.biglybt.core.util.FileUtil.reallyCopyFile
	 */
	private static boolean copyAndDelete(AndroidFile from_file, File to_file) {
		boolean success = false;

		// can't rename across file systems under Linux - try copy+delete

		FileInputStream from_is = null;
		FileOutputStream to_os = null;
		DirectByteBuffer buffer = null;

		try {
			final int BUFFER_SIZE = 128 * 1024;

			buffer = DirectByteBufferPool.getBuffer(DirectByteBuffer.AL_EXTERNAL,
					BUFFER_SIZE);

			ByteBuffer bb = buffer.getBuffer(DirectByteBuffer.SS_EXTERNAL);

			from_is = FileUtil.newFileInputStream(from_file);
			to_os = FileUtil.newFileOutputStream(to_file);

			FileChannel from_fc = from_is.getChannel();
			FileChannel to_fc = to_os.getChannel();

			long rem = from_fc.size();

			while (rem > 0) {

				int to_read = (int) Math.min(rem, BUFFER_SIZE);

				bb.position(0);
				bb.limit(to_read);

				while (bb.hasRemaining()) {

					from_fc.read(bb);
				}

				bb.position(0);

				to_fc.write(bb);

				rem -= to_read;

			}

			from_is.close();

			from_is = null;

			to_os.close();

			to_os = null;

			if (!from_file.delete()) {
				Debug.out(
						"renameFile: failed to delete '" + from_file.toString() + "'");

				throw (new Exception(
						"Failed to delete '" + from_file.toString() + "'"));
			}

			success = true;

			return (true);

		} catch (Throwable e) {

			Debug.out("renameFile: failed to rename '" + from_file.toString()
					+ "' to '" + to_file.toString() + "'", e);

			return (false);

		} finally {

			if (from_is != null) {

				try {
					from_is.close();

				} catch (Throwable e) {
				}
			}

			if (to_os != null) {

				try {
					to_os.close();

				} catch (Throwable e) {
				}
			}

			if (buffer != null) {

				buffer.returnToPool();
			}

			// if we've failed then tidy up any partial copy that has been performed

			if (!success) {

				if (to_file.exists()) {

					to_file.delete();
				}
			}
		}
	}

	@Override
	public boolean setLastModified(long time) {
		log("setLastMofieid");
		notImplemented();
		return false;
	}

	@Override
	public boolean setReadOnly() {
		log("setReadOnly");
		notImplemented();
		return false;
	}

	@Override
	public boolean setWritable(boolean writable, boolean ownerOnly) {
		log("setWritable");
		notImplemented();
		return false;
	}

	@Override
	public boolean setWritable(boolean writable) {
		log("setwritable");
		notImplemented();
		return false;
	}

	@Override
	public boolean setReadable(boolean readable, boolean ownerOnly) {
		log("setReadable");
		notImplemented();
		return false;
	}

	@Override
	public boolean setReadable(boolean readable) {
		log("setReadable");
		notImplemented();
		return false;
	}

	@Override
	public boolean setExecutable(boolean executable, boolean ownerOnly) {
		log("setExecutable");
		notImplemented();
		return false;
	}

	@Override
	public boolean setExecutable(boolean executable) {
		log("setExecutable");
		notImplemented();
		return false;
	}

	@Override
	public boolean canExecute() {
		log("canExecute");
		notImplemented();
		return false;
	}

	@Override
	public long getTotalSpace() {
		log("getTotalSpace");
		notImplemented();
		return super.getTotalSpace();
	}

	@Override
	public long getFreeSpace() {
		PathInfo pathInfo = getPathInfo();
		log("getFreeSpace=" + pathInfo.freeBytes);
		return pathInfo.freeBytes;
	}

	@Override
	public long getUsableSpace() {
		PathInfo pathInfo = getPathInfo();
		log("getUsableSpace=" + pathInfo.freeBytes);
		return pathInfo.freeBytes;
	}

	@NonNull
	public PathInfo getPathInfo() {
		if (pathInfo == null) {
			pathInfo = PathInfo.buildPathInfo(path);
		}
		return pathInfo;
	}

	@Override
	public int compareTo(@NonNull File pathname) {
		log("compareTo");
		if (pathname instanceof AndroidFile) {
			return (path.compareTo((((AndroidFile) pathname).path)));
		}
		return super.compareTo(pathname);
	}

	@Override
	public boolean equals(@Nullable Object obj) {
		if (obj instanceof AndroidFile) {
			// DocumentFile doesn't override equals.  uri does toString() comparison
			boolean equals = path.equals((((AndroidFile) obj).path));
			log("equals=" + equals);
			return equals;
		}
		return super.equals(obj);
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	@NonNull
	@Override
	public String toString() {
		if (DEBUG_CALLS_SPAM) {
			log("toString");
		}

		return path;
	}

	@RequiresApi(api = VERSION_CODES.O)
	@NonNull
	@Override
	public Path toPath() {
		log("toPath");

		return Paths.get(URI.create(path));
	}

	private static void notImplemented() {
		Log.e(TAG, "not implemented. " + AndroidUtils.getCompressedStackTrace());
	}

	@SuppressLint("LogConditional")
	@AssumeNoSideEffects
	void log(String s) {
		if (!DEBUG_CALLS) {
			return;
		}
		Log.d(TAG, s + "] " + getShortName() + " via "
				+ AndroidUtils.getCompressedStackTrace(1, 12));
	}

	@SuppressWarnings("WeakerAccess")
	@SuppressLint("LogConditional")
	@AssumeNoSideEffects
	void logw(String s) {
		if (!DEBUG_CALLS) {
			return;
		}
		Log.w(TAG, s + "] " + getShortName() + " via "
				+ AndroidUtils.getCompressedStackTrace(1, 12));
	}

	@SuppressWarnings("WeakerAccess")
	@SuppressLint("LogConditional")
	@AssumeNoSideEffects
	void loge(String s, Throwable t) {
		if (!DEBUG_CALLS) {
			return;
		}
		Log.e(TAG, s + "] " + getShortName() + " via "
				+ AndroidUtils.getCompressedStackTrace(1, 12), t);
	}

	private String getShortName() {
		int i = path.indexOf('/', 11);
		return i > 0 ? path.substring(i + 1) : path;
	}

	@NonNull
	DocumentFile getDocFile() {
		if (needsBuilding) {
			build(false);
		}
		return docFile;
	}

	@NonNull
	public Uri getUri() {
		if (needsBuilding) {
			build(false);
		}
		return uri;
	}

	private void build(boolean skipRealPathCheck) {
		if (DEBUG_CALLS_SPAM && (docFile == null || needsBuilding)) {
			log("build" + (docFile == null ? "+DocFile" : "")
					+ (needsBuilding ? "+Needed" : ""));
		}
		Context context = BiglyBTApp.getContext();
		if (docFile == null) {
			DocumentFile docFile = DocumentFile.fromTreeUri(context,
					Uri.parse(fixDirName(path)));
			if (docFile == null) {
				throw new IllegalArgumentException("Invalid path " + path);
			}

			this.docFile = docFile;
			// make it a document uri (adds /document/* to path)
			uri = docFile.getUri();
			path = uri.toString();
		}

		if (needsBuilding && !skipRealPathCheck) {
			String realPath = getRealPath();
			needsBuilding = realPath == null;
			if (realPath != null && !path.equals(realPath)) {
				log("realPath=" + realPath);
				docFile = DocumentFile.fromTreeUri(context, Uri.parse(realPath));
				uri = docFile.getUri();
				// We replace existing path, but it might make sense in the future to
				// store the real path separately as a canonical variable
				path = uri.toString();
			}
		}
	}

	private Uri getFirstFileUri() {
		final ContentResolver resolver = BiglyBTApp.getContext().getContentResolver();
		final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
				uri, DocumentsContract.getDocumentId(uri));

		Cursor c = null;
		try {
			c = resolver.query(childrenUri, new String[] {
				DocumentsContract.Document.COLUMN_DOCUMENT_ID
			}, null, null, null);
			while (c.moveToNext()) {
				final String documentId = c.getString(0);
				final Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(uri,
						documentId);
				return documentUri;
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed query: " + e);
		} finally {
			closeQuietly(c);
		}
		return null;
	}

	/**
	 * Converts special directories.
	 * <p/>
	 * For example:<br/>
	 *   <tt>content://com.android.providers.downloads.documents/tree/downloads/document/downloads</tt><br/>
	 * might actually be
	 *   <tt>content://com.android.providers.downloads.documents/tree/downloads/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload</tt><br/>
	 * for all files within the "downloads" folder.
	 */
	private String getRealPath() {
		try {
			if (!docFile.isDirectory()) {
				return null;
			}
			Uri uri = getFirstFileUri();
			if (uri == null) {
				DocumentFile tmp = docFile.createDirectory("tmp");
				if (tmp == null) {
					return null;
				}
				uri = tmp.getUri();
				tmp.delete();
			}
			String path = uri.toString();
			int i = path.lastIndexOf("%2F");
			if (i < 0) {
				return null;
			}
			path = path.substring(0, i);
			return path;
		} catch (Throwable t) {
			loge("getRealPath", t);
			return null;
		}
	}

}
