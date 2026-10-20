param([Parameter(Mandatory=$true)][string] $Template)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3
Add-Type -AssemblyName System.Net.Http
$parseTokens = $null; $parseErrors = $null
$tree = [Management.Automation.Language.Parser]::ParseFile($Template, [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'Bootstrap PowerShell syntax is invalid.' }
foreach ($functionName in @('Assert-TeamCodeUri', 'Receive-TeamCodeFile')) {
    $definition = $tree.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName }, $false)
    if ($null -eq $definition) { throw 'Missing bootstrap HTTP helper.' }
    . ([scriptblock]::Create($definition.Extent.Text))
}

# Exercise the real PowerShell download implementation through a credential-free
# in-memory HTTP handler. No DNS, certificate bypass or provider call is involved.
$fixtureSource = @'
using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
public class BootstrapFixtureHandler : HttpMessageHandler {
    public string Mode;
    public int Requests;
    public bool Cancellable;
    public BootstrapFixtureHandler(string mode) { Mode = mode; }
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
        Requests++; Cancellable = token.CanBeCanceled;
        var response = new HttpResponseMessage(HttpStatusCode.OK);
        if (Mode == "loop" || Mode == "http" || Mode == "foreign" || Mode == "credentials" || Mode == "query" || (Mode == "redirect" && Requests == 1)) {
            response.StatusCode = HttpStatusCode.Redirect;
            string location = "https://release-assets.githubusercontent.com/fixture?download=public";
            if (Mode == "http") location = "http://github.com/example/release";
            if (Mode == "foreign") location = "https://example.org/asset";
            if (Mode == "credentials") location = "https://user:password@release-assets.githubusercontent.com/asset";
            if (Mode == "query") location = "https://github.com/example/release?changed=1";
            response.Headers.Location = new Uri(location);
        } else {
            byte[] data = System.Text.Encoding.UTF8.GetBytes(Mode == "long" ? "abcd" : Mode == "short" ? "ab" : "abc");
            response.Content = new StreamContent(new BootstrapNonSeekable(data));
            if (Mode == "length") response.Content.Headers.ContentLength = 9;
            if (Mode == "encoded") response.Content.Headers.ContentEncoding.Add("gzip");
            if (Mode == "error") response.StatusCode = (HttpStatusCode)429;
        }
        return Task.FromResult(response);
    }
}
public class BootstrapNonSeekable : Stream {
    private readonly MemoryStream Body;
    public BootstrapNonSeekable(byte[] body) { Body = new MemoryStream(body); }
    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return false; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { throw new NotSupportedException(); } }
    public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
    public override int Read(byte[] buffer, int offset, int count) { return Body.Read(buffer, offset, count); }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
    public override void SetLength(long length) { throw new NotSupportedException(); }
    public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
    protected override void Dispose(bool disposing) { if (disposing) Body.Dispose(); base.Dispose(disposing); }
}
'@
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    Add-Type -TypeDefinition $fixtureSource -ReferencedAssemblies @([Net.Http.HttpClient].Assembly.Location, [Net.HttpStatusCode].Assembly.Location)
} else {
    Add-Type -TypeDefinition $fixtureSource
}

$directory = Join-Path ([IO.Path]::GetTempPath()) ('teamcode-bootstrap-http-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
try {
    foreach ($mode in @('good', 'redirect', 'loop', 'http', 'foreign', 'credentials', 'query', 'long', 'short', 'length', 'encoded', 'error')) {
        $handler = [BootstrapFixtureHandler]::new($mode)
        $client = [Net.Http.HttpClient]::new($handler)
        $destination = Join-Path $directory $mode
        $failed = $false
        try { Receive-TeamCodeFile $client ([Uri]'https://github.com/example/release') $destination 3 }
        catch { $failed = $true }
        finally { $client.Dispose() }
        if ($failed -ne ($mode -notin @('good', 'redirect'))) { throw "Unexpected bootstrap HTTP result: $mode" }
        if (-not $handler.Cancellable -or $handler.Requests -gt 6) { throw 'Download deadline or redirect bound is missing.' }
        if (-not $failed -and [IO.File]::ReadAllText($destination) -ne 'abc') { throw 'Download bytes changed.' }
        if ($mode -eq 'redirect' -and $handler.Requests -ne 2) { throw 'Redirect was not followed.' }
        if ($mode -eq 'loop' -and $handler.Requests -ne 6) { throw 'Redirect loop was not bounded.' }
        if ($mode -in @('http', 'foreign', 'credentials', 'query') -and $handler.Requests -ne 1) { throw 'Untrusted redirect was requested.' }
        if ((Test-Path -LiteralPath $destination) -and (Get-Item -LiteralPath $destination).Length -gt 3) { throw 'Unbounded download output.' }
    }
    Write-Output 'PASS: PowerShell syntax, HTTPS redirect policy, finite redirects/deadline, exact streamed lengths and encoded/error refusal.'
} finally { Remove-Item -LiteralPath $directory -Recurse -Force }
