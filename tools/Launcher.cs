using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

/// <summary>
/// Single-file launcher for Jar Analyzer.
///
/// The application ships as a directory (its own launcher, a bundled Java
/// runtime and the program jar), which cannot be handed to someone as one
/// download. This wraps that directory in a single executable: the payload is
/// embedded as a resource, unpacked once into the user's local app data, and the
/// real launcher is started from there. Later runs find the unpacked copy and
/// start immediately.
/// </summary>
internal static class Launcher
{
    private const string AppName = "Jar Analyzer";
    private const string Version = "2.1.0";

    [STAThread]
    private static int Main()
    {
        try
        {
            string root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "JarAnalyzer", Version);
            string exe = Path.Combine(root, AppName, AppName + ".exe");
            string stamp = Path.Combine(root, ".ready");

            // A named mutex stops a second double-click from unpacking into the
            // same folder while the first one is still writing to it.
            using (var gate = new Mutex(false, "Global\\JarAnalyzerUnpack-" + Version))
            {
                gate.WaitOne();
                try
                {
                    if (!File.Exists(stamp) || !File.Exists(exe))
                    {
                        Unpack(root);
                        File.WriteAllText(stamp, Version);
                    }
                }
                finally
                {
                    gate.ReleaseMutex();
                }
            }

            Process.Start(new ProcessStartInfo(exe)
            {
                WorkingDirectory = Path.GetDirectoryName(exe),
                UseShellExecute = false
            });
            return 0;
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "Jar Analyzer başlatılamadı:\n\n" + ex.Message,
                "Jar Analyzer", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    /// <summary>Unpacks the embedded payload, showing a splash while it runs.</summary>
    private static void Unpack(string root)
    {
        using (Form splash = CreateSplash())
        {
            splash.Show();
            Application.DoEvents();

            if (Directory.Exists(root)) Directory.Delete(root, true);
            Directory.CreateDirectory(root);

            string temp = Path.Combine(Path.GetTempPath(),
                "jaranalyzer-" + Guid.NewGuid().ToString("N") + ".zip");
            try
            {
                Assembly self = Assembly.GetExecutingAssembly();
                using (Stream src = self.GetManifestResourceStream("payload.zip"))
                using (Stream dst = File.Create(temp))
                {
                    if (src == null) throw new InvalidOperationException("payload.zip bulunamadı");
                    src.CopyTo(dst);
                }
                ZipFile.ExtractToDirectory(temp, root);
            }
            finally
            {
                try { File.Delete(temp); } catch { /* temp file, best effort */ }
            }
        }
    }

    private static Form CreateSplash()
    {
        var form = new Form
        {
            FormBorderStyle = FormBorderStyle.None,
            StartPosition = FormStartPosition.CenterScreen,
            Size = new Size(360, 90),
            BackColor = Color.FromArgb(0x1D, 0x0F, 0x12),
            ShowInTaskbar = false,
            TopMost = true
        };
        form.Controls.Add(new Label
        {
            Text = "Jar Analyzer hazırlanıyor…",
            ForeColor = Color.FromArgb(0xE8, 0x39, 0x4C),
            Font = new Font("Segoe UI", 11f, FontStyle.Bold),
            AutoSize = false,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter
        });
        return form;
    }
}
