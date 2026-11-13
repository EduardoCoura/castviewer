# Third-Party Notices (`firestick-app` AirPlay Receiver Native Engine)

This module (`app/src/main/cpp/`) integrates upstream open-source libraries under licenses compatible with CastViewer's GNU General Public License v3.0 (`GPL-3.0`):

1. **UxPlay (`third_party/uxplay/`)**
   - Repository: https://github.com/FDH2/UxPlay
   - Commit: `05b0275cb1b726592700e47a6e0307544b3d3a52` (`2026-11-06`)
   - License: GNU General Public License v3.0 (GPL-3.0) + LGPL-2.1+ headers (originally derived from RPiPlay and ShairPlay), embedding `llhttp` (MIT) and `playfair` (GPL-3.0).
   - Android adaptations applied cleanly in vendored files:
     - `android_dnssd_shim.{h,c}` replaces Avahi/Bonjour mDNS daemon calls with TXT-record synthesis consumed by Android `NsdManager`.
     - `raop.c`: ignores non-CSeq BLE beacon ping queries (`txtAirPlay`/`txtRAOP`) in the `nohold` session switch guard so discovery probes never interrupt an active stream.
     - `raop_handlers.h`: allows non-PIN `/pair-verify` fallback when `use_pin` is disabled for broader AirPlay sender compatibility.

2. **ShairPlay ALAC Decoder (`third_party/alac/`)**
   - Repository: https://github.com/juhovh/shairplay (`src/lib/alac/alac.{c,h}`)
   - Commit: `096b61ad14c90169f438e690d096e3fcf87e504e`
   - Copyright (c) 2005 David Hammerton — MIT / X11 License.

3. **libplist** (fetched at native configure time via CMake)
   - Repository: https://github.com/libimobiledevice/libplist (tag `2.6.0`)
   - License: GNU Lesser General Public License v2.1+ (LGPL-2.1+).

4. **OpenSSL (`openssl-cmake`)** (fetched at native configure time via CMake)
   - Repository: https://github.com/janbar/openssl-cmake (commit `b0ac69581958cd658364147da9057da89a01c394`, OpenSSL `1.1.1w`)
   - License: Apache-2.0 / OpenSSL License (build scripts MIT).
