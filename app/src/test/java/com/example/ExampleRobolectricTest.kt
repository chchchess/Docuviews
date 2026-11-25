package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app name matches DocuView`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DocuView", appName)
  }

  @Test
  fun `manifest resolves ACTION_VIEW for pdf doc docx xlsx`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pm = context.packageManager

    val cases =
      listOf(
        Uri.parse("content://com.example.files/laporan.pdf") to "application/pdf",
        Uri.parse("content://com.example.files/surat.doc") to "application/msword",
        Uri.parse("content://com.example.files/proposal.docx") to
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        Uri.parse("content://com.example.files/rekap.xlsx") to
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        Uri.parse("content://com.example.files/dokumen.final.xlsx") to "application/octet-stream",
      )

    for ((uri, mime) in cases) {
      val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime) }
      val resolved = pm.queryIntentActivities(intent, 0)
      assertTrue(
        "Expected MainActivity to resolve $uri ($mime)",
        resolved.any { it.activityInfo.packageName == context.packageName },
      )
    }
  }
}
