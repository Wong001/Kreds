// Task 3 (B.2d): IPC contract for the isolated-process AVIF decode boundary.
// The main process (which holds the content key and just decrypted the blob)
// passes ONE image's cleartext compressed AVIF bytes to the :imagedecode
// process over a pipe and reads decoded PNG bytes back over a second pipe.
// The isolated process never sees keys, ciphertext, the store, or a socket.
package expo.modules.tormanager;

import android.os.ParcelFileDescriptor;

interface IImageDecodeService {
    // Reads AVIF bytes from inputRead until EOF, decodes to a Bitmap via the
    // dav1d/libavif-backed decoder, re-encodes PNG and writes it to outputWrite,
    // then closes outputWrite. Returns true if a PNG was produced, false on any
    // failure. Bytes cross via ParcelFileDescriptor pipes, NOT Parcel args,
    // because decoded PNGs (and some AVIFs) exceed Binder's ~1 MB transaction
    // cap. outputWrite is closed on every path so the caller's reader always
    // sees EOF (empty output on failure -> caller returns null -> UI placeholder).
    boolean decode(in ParcelFileDescriptor inputRead, in ParcelFileDescriptor outputWrite);
}
