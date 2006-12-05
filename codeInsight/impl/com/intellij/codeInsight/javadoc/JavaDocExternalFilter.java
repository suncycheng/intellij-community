package com.intellij.codeInsight.javadoc;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.io.UrlConnectionUtil;
import com.intellij.util.net.HttpConfigurable;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Future;

/**
 * Created by IntelliJ IDEA.
 * User: db
 * Date: May 2, 2003
 * Time: 8:35:34 PM
 * To change this template use Options | File Templates.
 */

public class JavaDocExternalFilter {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.javadoc.JavaDocExternalFilter");

  private Project myProject;
  private PsiManager myManager;

  private static @NonNls Pattern ourHTMLsuffix = Pattern.compile("[.][hH][tT][mM][lL]?");
  private static @NonNls Pattern ourParentFolderprefix = Pattern.compile("^[.][.]/");
  private static @NonNls Pattern ourAnchorsuffix = Pattern.compile("#(.*)$");
  private static @NonNls Pattern ourHTMLFilesuffix = Pattern.compile("/[^/]*[.][hH][tT][mM][lL]?$");
  private static @NonNls Pattern ourHREFselector = Pattern.compile("<A[ \\t\\n\\r\\f]+HREF=\"([^>]*)\"");
  private static @NonNls Pattern ourAnnihilator = Pattern.compile("/[^/^.]*/[.][.]/");
  private static @NonNls Pattern ourIMGselector = Pattern.compile("<IMG[ \\t\\n\\r\\f]+SRC=\"([^>]*)\"");
  private static @NonNls Pattern ourMethodHeading = Pattern.compile("<H3>(.+)</H3>");
  private static @NonNls final String DOC_ELEMENT_PROTOCOL = "doc_element://";
  private static @NonNls final String PSI_ELEMENT_PROTOCOL = "psi_element://";
  private static @NonNls final String JAR_PROTOCOL = "jar:";
  private static @NonNls final String DOC_START_MARKER = "Generated by javadoc";
  @NonNls private static final String HR = "<HR>";
  @NonNls private static final String P = "<P>";
  @NonNls private static final String DL = "<DL>";
  @NonNls private static final String H2 = "</H2>";
  @NonNls private static final String HTML_CLOSE = "</HTML>";
  @NonNls private static final String HTML = "<HTML>";
  @NonNls private static final String BR = "<BR>";
  @NonNls private static final String DT = "<DT>";

  private static abstract class RefConvertor {
    private Pattern mySelector;

    public RefConvertor(Pattern selector) {
      mySelector = selector;
    }

    protected abstract String convertReference(String root, String href);

    public String refFilter(String root, String read) {
      String toMatch = read.toUpperCase();
      StringBuffer ready = new StringBuffer();
      int prev = 0;
      Matcher matcher = mySelector.matcher(toMatch);

      while (matcher.find()) {
        String before = read.substring(prev, matcher.start(1) - 1);     // Before reference
        String href = read.substring(matcher.start(1), matcher.end(1)); // The URL

        prev = matcher.end(1) + 1;
        ready.append(before);
        ready.append("\"");
        ready.append(convertReference(root, href));
        ready.append("\"");
      }

      ready.append(read.substring(prev, read.length()));

      return ready.toString();
    }
  }

  private RefConvertor myIMGConvertor = new RefConvertor(ourIMGselector) {
    protected String convertReference(String root, String href) {
      if (StringUtil.startsWithChar(href, '#')) {
        return DOC_ELEMENT_PROTOCOL + root + href;
      }

      return ourHTMLFilesuffix.matcher(root).replaceAll("/") + href;
    }
  };

  private RefConvertor[] myReferenceConvertors = new RefConvertor[]{
    new RefConvertor(ourHREFselector) {
      protected String convertReference(String root, String href) {
        if (BrowserUtil.isAbsoluteURL(href)) {
          return href;
        }

        if (StringUtil.startsWithChar(href, '#')) {
          return DOC_ELEMENT_PROTOCOL + root + href;
        }

        String nakedRoot = ourHTMLFilesuffix.matcher(root).replaceAll("/");

        String stripped = ourHTMLsuffix.matcher(href).replaceAll("");
        int len = stripped.length();

        do stripped = ourParentFolderprefix.matcher(stripped).replaceAll(""); while (len > (len = stripped.length()));

        final String elementRef = stripped.replaceAll("/", ".");
        final String classRef = ourAnchorsuffix.matcher(elementRef).replaceAll("");

        return
          (myManager.findClass(classRef, GlobalSearchScope.allScope(myProject)) != null)
          ? PSI_ELEMENT_PROTOCOL + elementRef
          : DOC_ELEMENT_PROTOCOL + doAnnihilate(nakedRoot + href);
      }
    },

    myIMGConvertor
  };

  public JavaDocExternalFilter(Project project) {
    myProject = project;
    myManager = PsiManager.getInstance(myProject);
  }

  private static String doAnnihilate(String path) {
    int len = path.length();

    do {
      path = ourAnnihilator.matcher(path).replaceAll("/");
    }
    while (len > (len = path.length()));

    return path;
  }

  private interface Waiter{
    void sayYes();

    boolean runMe();
  }

  public static boolean isJavaDocURL(final String url) throws Exception {
    final Waiter waiter = new Waiter(){
      Boolean key = Boolean.FALSE;
      final Object LOCK = new Object();

      public void sayYes(){
        key = Boolean.TRUE;
        synchronized (LOCK) {
          LOCK.notify();
        }
      }

      public boolean runMe(){
        try {
          synchronized (LOCK) {
            LOCK.wait(600);
          }
        }
        catch (InterruptedException e) {
          return false;
        }

        return key.booleanValue();
      }
    };

    final boolean[] fail = new boolean[1];
    final Exception [] ex = new Exception[1];
    ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
      public void run() {
        Reader stream = null;
        try {
          stream = getReaderByUrl(url, ProgressManager.getInstance().getProgressIndicator());
        }
        catch (IOException e) {
          ex[0] = e;
        }
        if (stream == null) {
          fail[0] = true;
          return;
        }
        try {
          BufferedReader reader = null;
          try {
            reader = new BufferedReader(stream);
            int lookUp = 6;

            while (lookUp > 0) {
              if (reader.readLine().indexOf(DOC_START_MARKER) != -1) {
                waiter.sayYes();
              }

              lookUp--;
            }
          }
          finally {
            if (reader != null) {
              reader.close();
            }
          }


        }
        catch (final Exception e) {
          ex[0] = e;
        }
      }
    });

    if (ex[0] != null) {
      throw ex[0];
    }
    return !fail[0] && waiter.runMe();
  }

  private String correctRefs(String root, String read) {
    String result = read;

    for (RefConvertor myReferenceConvertor : myReferenceConvertors) {
      result = myReferenceConvertor.refFilter(root, result);
    }

    return result;
  }

  @Nullable
  public String filterInternalDocInfo(String text, String surl) {
    if (text == null) {
      return null;
    }

    text = JavaDocUtil.fixupText(text);

    if (surl == null) {
      return text;
    }

    String root = ourAnchorsuffix.matcher(surl).replaceAll("");

    return correctRefs(root, text);
  }


  @Nullable
  private static Reader getReaderByUrl(final String surl, final ProgressIndicator pi) throws IOException {
    if (surl.startsWith(JAR_PROTOCOL)) {
      VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(BrowserUtil.getDocURL(surl));

      if (file == null) {
        return null;
      }

      return new StringReader(VfsUtil.loadText(file));
    }

    URL url = BrowserUtil.getURL(surl);
    HttpConfigurable.getInstance().prepareURL(url.toString());
    final URLConnection urlConnection = url.openConnection();
    final String contentEncoding = urlConnection.getContentEncoding();
    final InputStream inputStream =
      pi != null ? UrlConnectionUtil.getConnectionInputStreamWithException(urlConnection, pi) : urlConnection.getInputStream();
    //noinspection IOResourceOpenedButNotSafelyClosed
    return contentEncoding != null ? new InputStreamReader(inputStream, contentEncoding) : new InputStreamReader(inputStream);
  }

  @Nullable
  @SuppressWarnings({"HardCodedStringLiteral"})
  public String getExternalDocInfo(final String surl) throws Exception {
    if (surl == null) return null;    
    if (MyJavadocFetcher.isFree()) {
      final MyJavadocFetcher fetcher = new MyJavadocFetcher(surl);
      final Future<?> fetcherFuture = ApplicationManager.getApplication().executeOnPooledThread(fetcher);
      try {
        fetcherFuture.get();
      }
      catch (Exception e) {
        return null;
      }
      final Exception exception = fetcher.getException();
      if (exception != null) {
        fetcher.cleanup();
        throw exception;
      }

      final String docText = correctRefs(ourAnchorsuffix.matcher(surl).replaceAll(""), fetcher.getData());
      if (LOG.isDebugEnabled()) {
        LOG.debug("Filtered JavaDoc: " + docText + "\n");
      }
      return JavaDocUtil.fixupText(docText);
    }
    return null;
  }

  @Nullable
   public String getExternalDocInfoForElement(final String docURL, final PsiElement element) throws Exception {
     String externalDoc = getExternalDocInfo(docURL);
     if (externalDoc != null) {
       if (element instanceof PsiMethod) {
         String className = ((PsiMethod) element).getContainingClass().getQualifiedName();
         Matcher matcher = ourMethodHeading.matcher(externalDoc);
         //noinspection HardCodedStringLiteral
         return matcher.replaceFirst("<H3>" + className + "</H3>");
      }
    }
    return externalDoc;
  }

  private static class MyJavadocFetcher implements Runnable {
    private static boolean ourFree = true;
    private final StringBuffer data = new StringBuffer();
    private final String surl;
    private final Exception [] myExceptions = new Exception[1];

    public MyJavadocFetcher(final String surl) {
      this.surl = surl;
      ourFree = false;
    }

    public static boolean isFree() {
      return ourFree;
    }

    public String getData() {
      return data.toString();
    }

    public void run() {
      try {
        if (surl == null) {
          return;
        }

        Reader stream = null;
        try {
          stream = getReaderByUrl(surl, new ProgressIndicatorBase());
        }
        catch (ProcessCanceledException e) {
          return;
        }
        catch (IOException e) {
          myExceptions[0] = e;
        }

        if (stream == null) {
          return;
        }

        Matcher anchorMatcher = ourAnchorsuffix.matcher(surl);
        @NonNls String startSection = "<!-- ======== START OF CLASS DATA ======== -->";
        @NonNls String endSection = "SUMMARY ======== -->";
        boolean isClassDoc = true;

        if (anchorMatcher.find()) {
          isClassDoc = false;
          startSection = "<A NAME=\"" + anchorMatcher.group(1).toUpperCase() + "\"";
          endSection = "<A NAME=";
        }

        final BufferedReader buf = new BufferedReader(stream);

        data.append(HTML);
        try {
          String read;

          do {
            read = buf.readLine();
          }
          while (read != null && read.toUpperCase().indexOf(startSection) == -1);


          if (read == null) {
            data.delete(0, data.length());
            return;
          }

          data.append(read);

          if (isClassDoc) {
            boolean skip = false;

            while (((read = buf.readLine()) != null) && !read.toUpperCase().equals(DL)) {
              if (read.toUpperCase().indexOf(H2) != -1) { // read=class name in <H2>
                data.append(H2);
                skip = true;
              }
              else if (!skip) data.append(read); //correctRefs(root, read));
            }

            data.append(DL);

            StringBuffer classDetails = new StringBuffer();

            while (((read = buf.readLine()) != null) && !read.toUpperCase().equals(HR)) {
              classDetails.append(read); //correctRefs(root, read));
            }

            while (((read = buf.readLine()) != null) && !read.toUpperCase().equals(P)) {
              data.append(read.replaceAll(DT, DT + BR)); //correctRefs(root, read));
            }

            data.append(classDetails);
            data.append(P);
          }

          while (((read = buf.readLine()) != null) && read.indexOf(endSection) == -1) {
            if (read.toUpperCase().indexOf(HR) == -1) {
              data.append(read); //correctRefs(root, read));
            }
          }

          data.append(HTML_CLOSE);

        }
        catch (final IOException e) {
          myExceptions[0] = e;
        }
        finally {
          if (buf != null) {
            try {
              buf.close();
            }
            catch (IOException e) {
              myExceptions[0] = e;
            }
          }
        }
      }
      finally {
        ourFree = true;
      }
    }

    public Exception getException() {
      return myExceptions[0];
    }

    public void cleanup() {
      myExceptions[0] = null;
    }
  }
}
