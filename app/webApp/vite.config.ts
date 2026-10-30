import { defineConfig, loadEnv, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The page runs nothing from elsewhere and tells no one where it came from. Built pages only: the dev server
 * injects inline scripts for fast refresh. The static host may send the same policy as a header.
 */
function contentSecurityPolicy(tilesUrl: string | undefined): Plugin {
  const tilesOrigin = tilesUrl && /^https?:\/\//.test(tilesUrl) ? ` ${new URL(tilesUrl).origin}` : '';
  const policy = [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
    `connect-src 'self'${tilesOrigin}`,
    "img-src 'self' data: blob:",
    "worker-src 'self' blob:",
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ');
  return {
    name: 'hashiru-content-security-policy',
    apply: 'build',
    transformIndexHtml: (html) =>
      html.replace('<head>', `<head>\n    <meta http-equiv="Content-Security-Policy" content="${policy}">`),
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_');
  return {
    root: '.',
    plugins: [react(), contentSecurityPolicy(env.VITE_PMTILES_URL)],
    build: {
      outDir: 'dist',
      emptyOutDir: true,
    },
    server: {
      port: 5173,
      // As the static host's proxy does in production, `/api` goes to the relay (`./gradlew :server:run`).
      proxy: { '/api': 'http://localhost:8080' },
    },
  };
});
