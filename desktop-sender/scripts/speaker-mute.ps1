param (
    [Parameter(Mandatory=$false)]
    [ValidateSet("get", "set", "mute", "unmute")]
    [string]$Action = "get",

    [Parameter(Mandatory=$false)]
    [string]$Mute = ""
)

$ErrorActionPreference = "Stop"

$csSource = @'
using System;
using System.Runtime.InteropServices;

namespace CastViewerAudio {
    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MMDeviceEnumeratorComObject { }

    internal enum EDataFlow {
        eRender = 0,
        eCapture,
        eAll
    }

    internal enum ERole {
        eConsole = 0,
        eMultimedia,
        eCommunications
    }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator {
        int NotUsed();
        [PreserveSig]
        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice ppEndpoint);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice {
        [PreserveSig]
        int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, out IAudioEndpointVolume ppInterface);
    }

    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioEndpointVolume {
        int NotUsed1();
        int NotUsed2();
        int NotUsed3();
        int NotUsed4();
        int SetMasterVolumeLevelScalar(float fLevel, Guid pguidEventContext);
        int NotUsed5();
        int GetMasterVolumeLevelScalar(out float pfLevel);
        int NotUsed6();
        int NotUsed7();
        int NotUsed8();
        int NotUsed9();
        [PreserveSig]
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, Guid pguidEventContext);
        [PreserveSig]
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool pbMute);
    }

    public static class SpeakerControl {
        private static IAudioEndpointVolume GetMasterVolume() {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev = null;
            int hr = enumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out dev);
            if (hr != 0 || dev == null) return null;

            Guid iid = typeof(IAudioEndpointVolume).GUID;
            IAudioEndpointVolume vol = null;
            hr = dev.Activate(ref iid, 23 /* CLSCTX_ALL */, IntPtr.Zero, out vol);
            if (hr != 0 || vol == null) return null;
            return vol;
        }

        public static bool GetMute() {
            var vol = GetMasterVolume();
            if (vol == null) return false;
            bool mute = false;
            vol.GetMute(out mute);
            return mute;
        }

        public static bool SetMute(bool mute) {
            var vol = GetMasterVolume();
            if (vol == null) return false;
            int hr = vol.SetMute(mute, Guid.Empty);
            return hr == 0;
        }
    }
}
'@

try {
    if (-not ([System.Management.Automation.PSTypeName]'CastViewerAudio.SpeakerControl').Type) {
        Add-Type -TypeDefinition $csSource -Language CSharp
    }

    if ($Action -eq "get") {
        $cur = [CastViewerAudio.SpeakerControl]::GetMute()
        $json = @{
            success = $true
            muted = [bool]$cur
        } | ConvertTo-Json -Compress
        Write-Output $json
        exit 0
    }

    $targetMute = $false
    if ($Action -eq "mute") {
        $targetMute = $true
    } elseif ($Action -eq "unmute") {
        $targetMute = $false
    } elseif ($Action -eq "set") {
        if ($Mute -eq "1" -or $Mute -eq "true" -or $Mute -eq "True") {
            $targetMute = $true
        } else {
            $targetMute = $false
        }
    }

    $ok = [CastViewerAudio.SpeakerControl]::SetMute($targetMute)
    $actual = [CastViewerAudio.SpeakerControl]::GetMute()
    $json = @{
        success = [bool]$ok
        muted = [bool]$actual
    } | ConvertTo-Json -Compress
    Write-Output $json
    exit 0
} catch {
    $errJson = @{
        success = $false
        error = $_.Exception.Message
    } | ConvertTo-Json -Compress
    Write-Output $errJson
    exit 1
}
