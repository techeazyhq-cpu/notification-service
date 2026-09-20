import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, /v1 is proxied to the Client API so the browser sees a single origin (no CORS needed).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: { '/v1': process.env.CLIENT_API_URL || 'http://localhost:8080' },
  },
});
