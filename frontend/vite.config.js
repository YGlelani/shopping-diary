
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ command }) => {
  const isDevelopment = command === 'serve';

  return {
    plugins: [
      react(),
      tailwindcss(),
    ],

    // Ensure all dependencies resolve the same React instance.
    resolve: {
      dedupe: [
        'react',
        'react-dom',
      ],
    },

    // Rebuild a consistent set of React dependencies.
    optimizeDeps: {
      include: [
        'react',
        'react-dom',
        'react-dom/client',
        'react-router-dom',
      ],
    },

    // Local development only.
    // Vercel production builds do not use this proxy.
    ...(isDevelopment
      ? {
          server: {
            host: 'localhost',
            port: 5173,
            strictPort: true,

            proxy: {
              '/api': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
              },
            },
          },
        }
      : {}),
  };
});
