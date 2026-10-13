/**
 * #120: the activation link `https://vesqen-api.sumirenokai.com/activate#<code>` lives on this host
 * because Chrome keeps a link that stays on the current site in the browser; a link on the website
 * could never leave its own purchase page for the app (docs/ACTIVATION_CODES.md).
 *
 * - /.well-known/assetlinks.json lets Android verify that the app may open the link. It names the
 *   application signing certificate, which is public (docs/RELEASE_SIGNING.md).
 * - /activate, opened in a browser (no app installed, or the link not verified), redirects to the
 *   website's activation page. Browsers keep the #code across the redirect; it never reaches here.
 *
 * Both answer whether or not sales are open: Android checks the file when the app is installed.
 */
const ASSET_LINKS = JSON.stringify([{
  relation:['delegate_permission/common.handle_all_urls'],
  target:{
    namespace:'android_app',
    package_name:'io.github.sumirenokai.vesqen',
    sha256_cert_fingerprints:['74:3E:96:FC:B7:1D:C5:81:88:49:68:19:A0:01:A2:7C:D8:8D:90:91:62:C5:AE:92:9C:87:BF:A2:9A:B8:62:93'],
  },
}]);
const WEBSITE = 'https://vesqen.sumirenokai.com';

export const isAppLinkPath = path => path === '/.well-known/assetlinks.json' || path === '/activate' || path === '/activate/';

export function handleAppLink(request, url) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response(null, {status:405, headers:{allow:'GET, HEAD', 'cache-control':'no-store'}});
  }
  if (url.pathname === '/.well-known/assetlinks.json') {
    return new Response(request.method === 'HEAD' ? null : ASSET_LINKS, {status:200, headers:{
      'content-type':'application/json', 'cache-control':'public, max-age=3600', 'x-content-type-options':'nosniff',
    }});
  }
  // The website picks its language only on the home page, so choose the page here: Chinese when the
  // browser's first language is Chinese. The header is read for this answer only, never stored.
  const first = (request.headers.get('accept-language') ?? '').split(',')[0].trim().toLowerCase();
  const page = first.startsWith('zh') ? '/zh/activate/' : '/activate/';
  return new Response(null, {status:302, headers:{location:`${WEBSITE}${page}`, 'cache-control':'no-store', vary:'accept-language'}});
}
