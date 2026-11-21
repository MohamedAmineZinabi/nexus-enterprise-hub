import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

/**
 * Set to `true` on background requests (health probes, polling) to suppress
 * global error toasts. 401 handling (session teardown) still applies.
 */
export const SILENT_HTTP_ERRORS = new HttpContextToken<boolean>(() => false);

const AUTH_ENDPOINTS = ['/api/v1/auth/login', '/api/v1/auth/register', '/api/v1/auth/logout'];

/**
 * True when the error interceptor already surfaced a global notification for
 * this error, so feature components can avoid showing a duplicate toast.
 */
export function isGloballyHandledHttpError(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse)) return false;
  return error.status === 0 || error.status === 401 || error.status === 403 || error.status === 429 || error.status >= 500;
}

function retryAfterSeconds(error: HttpErrorResponse): number | null {
  const header = error.headers?.get('Retry-After');
  if (!header) return null;
  const seconds = Number(header);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds);
  const date = Date.parse(header);
  return Number.isNaN(date) ? null : Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

/**
 * Functional HTTP error interceptor.
 * - 401: token invalid/expired/revoked -> flush session and redirect to login.
 * - 403 / 429 / 5xx / network (0): surface a user-facing toast, keep the session.
 * Errors are always rethrown so callers can react.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const notifications = inject(NotificationService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // Let login/register render their own "invalid credentials" state
      const isAuthEndpoint = AUTH_ENDPOINTS.some(path => req.url.includes(path));
      const silent = req.context.get(SILENT_HTTP_ERRORS);

      if (error.status === 401) {
        if (!isAuthEndpoint) {
          authService.logout();
          notifications.warning('Session expired', 'Please sign in again.');
        }
      } else if (!silent) {
        if (error.status === 403) {
          notifications.error('Access denied', 'You do not have permission to perform this action.');
        } else if (error.status === 429) {
          const wait = retryAfterSeconds(error);
          notifications.warning(
            'Too many requests',
            wait !== null ? `Rate limit reached. Please retry in ${wait}s.` : 'Rate limit reached. Please slow down and retry shortly.'
          );
        } else if (error.status === 503) {
          notifications.error('Service temporarily unavailable', 'A backend dependency is unavailable. Please retry in a moment.');
        } else if (error.status >= 500) {
          notifications.error('Server error', 'The server could not complete the request. Please retry.');
        } else if (error.status === 0) {
          notifications.error('Network error', 'Unable to reach the server. Check your connection.');
        }
      }

      return throwError(() => error);
    })
  );
};
