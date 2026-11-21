$ErrorActionPreference = 'Stop'
# Process messages concurrently so a waiting phone approval keeps stdin available.
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Net;
using System.Text;
using System.Threading.Tasks;
public static class MikeMcpStdio {
    static readonly object Output = new object();
    static readonly string Url = "http://127.0.0.1:" + int.Parse(Environment.GetEnvironmentVariable("MIKE_MCP_PORT")) + "/mcp";
    static readonly string Authorization = "Bearer " + Environment.GetEnvironmentVariable("MIKE_MCP_TOKEN");
    public static void Run() {
        Console.InputEncoding = new UTF8Encoding(false);
        Console.OutputEncoding = new UTF8Encoding(false);
        ServicePointManager.DefaultConnectionLimit = 32;
        string line;
        while ((line = Console.ReadLine()) != null) {
            string packet = line;
            Task.Run(() => Forward(packet));
        }
    }
    static void Forward(string packet) {
        try {
            var request = (HttpWebRequest)WebRequest.Create(Url);
            request.Proxy = null;
            request.AllowAutoRedirect = false;
            request.KeepAlive = false;
            request.Timeout = System.Threading.Timeout.Infinite;
            request.ReadWriteTimeout = System.Threading.Timeout.Infinite;
            request.Method = "POST";
            request.Headers["Authorization"] = Authorization;
            request.ContentType = "application/json";
            var body = Encoding.UTF8.GetBytes(packet);
            request.ContentLength = body.Length;
            using (var stream = request.GetRequestStream()) stream.Write(body, 0, body.Length);
            using (var response = (HttpWebResponse)request.GetResponse())
            using (var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8)) {
                if (response.StatusCode != HttpStatusCode.OK && response.StatusCode != HttpStatusCode.Accepted) Environment.Exit(1);
                var reply = reader.ReadToEnd().TrimEnd();
                if (reply.Length != 0) lock (Output) { Console.WriteLine(reply); Console.Out.Flush(); }
            }
        } catch { Environment.Exit(1); }
    }
}
'@
[MikeMcpStdio]::Run()
