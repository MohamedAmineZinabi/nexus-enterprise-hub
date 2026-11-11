import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Functional route guard (CanActivateFn) that prevents unauthenticated users
 * from accessing protected enterprise shell modules.
 */
export const authGuard: CanActivateFn = (route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  // If token is present in storage or state is authenticated
  if (authService.isAuthenticated() || authService.getToken()) {
    return true;
  }

  // Redirect unauthenticated visitor to login with query param returnUrl
  return router.createUrlTree(['/auth/login'], {
    queryParams: { returnUrl: state.url }
  });
};

/**
 * Functional route guard that redirects authenticated users away from
 * public auth pages (login / register) directly to the dashboard.
 */
export const publicOnlyGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated() || authService.getToken()) {
    return router.createUrlTree(['/dashboard']);
  }

  return true;
};

