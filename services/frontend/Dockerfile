# ==============================================================================
# Multi-Stage Build Dockerfile for NexusEnterprise Hub - Angular 18+ Frontend
# ==============================================================================

# --- Stage 1: Build Production Angular Bundle ---
FROM node:20-alpine AS builder

WORKDIR /app

# Install npm dependencies cleanly using lockfile
COPY package.json package-lock.json ./
RUN npm ci

# Copy application source and build optimized production bundle
COPY . .
RUN npm run build

# --- Stage 2: Production NGINX Reverse Proxy & Static Server ---
FROM nginx:1.27-alpine

# Remove default site config and install SPA + SSE-friendly NGINX configuration
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy compiled Angular application browser assets
COPY --from=builder /app/dist/frontend/browser /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=10s \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:80/nginx-health || exit 1

CMD ["nginx", "-g", "daemon off;"]
