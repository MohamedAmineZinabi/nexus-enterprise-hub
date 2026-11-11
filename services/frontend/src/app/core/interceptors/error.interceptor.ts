import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * Functional HTTP error interceptor that intercepts 401 Unauthorized
 * or 403 Forbidden responses, flushes authentication state, and redirects
 * the client to the login screen.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // Don't intercept 401 on login endpoint itself so the component can render the invalid credentials badge
      const isAuthEndpoint = req.url.includes('/api/v1/auth/login') || req.url.includes('/api/v1/auth/register');

      if (!isAuthEndpoint && (error.status === 401 || error.status === 403)) {
        authService.logout();
      }

      return throwError(() => error);
    })
  );
};

