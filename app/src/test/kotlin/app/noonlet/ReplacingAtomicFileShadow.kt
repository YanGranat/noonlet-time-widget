package app.noonlet

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Uses the host filesystem's atomic replacement operation for a completed commit. */
@Implements(AtomicFile::class)
class ReplacingAtomicFileShadow {
    @RealObject lateinit var file: AtomicFile

    @Implementation(minSdk = 29)
    protected fun finishWrite(output: FileOutputStream?) {
        Shadow.directlyOn<Any, AtomicFile>(file, AtomicFile::class.java, "finishWrite",
            ClassParameter.from(FileOutputStream::class.java, output))
        val staged = File(file.baseFile.path + ".new")
        if (output != null && staged.isFile)
            Files.move(staged.toPath(), file.baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
