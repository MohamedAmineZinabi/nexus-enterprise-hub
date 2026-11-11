import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, of, map } from 'rxjs';
import {
  ApiResponse,
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  Tenant,
  User,
  UserProfileResponse
} from '../models/auth.models';

const TOKEN_KEY = 'nexus_jwt_token';
const USER_KEY = 'nexus_user_profile';
const TENANT_KEY = 'nexus_tenant_profile';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  // Reactive State via Angular 18+ Signals
  readonly currentUser = signal<User | null>(null);
  readonly currentTenant = signal<Tenant | null>(null);
  readonly isLoading = signal<boolean>(false);
  readonly isAuthenticated = computed(() => !!this.currentUser());

  constructor() {
    this.restoreSession();
  }

  /**
   * Retrieves the current JWT string from memory or local storage.
   */
  getToken(): string | null {
    if (typeof window === 'undefined') return null;
    return localStorage.getItem(TOKEN_KEY);
  }

  /**
   * Logs in a user with credentials and updates reactive signals.
   */
  login(credentials: LoginRequest): Observable<AuthResponse> {
    this.isLoading.set(true);
    return this.http.post<ApiResponse<AuthResponse>>('/api/v1/auth/login', credentials).pipe(
      map(res => res.data),
      tap(authData => {
        this.setSession(authData);
        this.isLoading.set(false);
      }),
      catchError(err => {
        this.isLoading.set(false);
        throw err;
      })
    );
  }

  /**
   * Registers a new organization and tenant administrator.
   */
  register(payload: RegisterRequest): Observable<AuthResponse> {
    this.isLoading.set(true);
    return this.http.post<ApiResponse<AuthResponse>>('/api/v1/auth/register', payload).pipe(
      map(res => res.data),
      tap(authData => {
        this.setSession(authData);
        this.isLoading.set(false);
      }),
      catchError(err => {
        this.isLoading.set(false);
        throw err;
      })
    );
  }

  /**
   * Fetches latest profile details from `/api/v1/auth/me`.
   */
  loadCurrentUser(): Observable<UserProfileResponse | null> {
    const token = this.getToken();
    if (!token) {
      this.clearSession();
      return of(null);
    }

    return this.http.get<ApiResponse<UserProfileResponse>>('/api/v1/auth/me').pipe(
      map(res => res.data),
      tap(profile => {
        const user: User = {
          id: profile.id,
          email: profile.email,
          fullName: profile.fullName,
          role: profile.role,
          tenantId: profile.tenant.id,
          organizationName: profile.tenant.name
        };
        const tenant: Tenant = {
          id: profile.tenant.id,
          name: profile.tenant.name,
          slug: profile.tenant.slug,
          status: profile.tenant.status
        };

        this.currentUser.set(user);
        this.currentTenant.set(tenant);

        if (typeof window !== 'undefined') {
          localStorage.setItem(USER_KEY, JSON.stringify(user));
          localStorage.setItem(TENANT_KEY, JSON.stringify(tenant));
        }
      }),
      catchError(() => {
        this.clearSession();
        return of(null);
      })
    );
  }

  /**
   * Revokes current session and redirects to login view.
   */
  logout(): void {
    const token = this.getToken();
    if (token) {
      this.http.post('/api/v1/auth/logout', {}).pipe(
        catchError(() => of(null))
      ).subscribe();
    }
    this.clearSession();
    this.router.navigate(['/auth/login']);
  }

  /**
   * Sets token and initial signals from login/register response.
   */
  private setSession(auth: AuthResponse): void {
    if (typeof window === 'undefined') return;

    localStorage.setItem(TOKEN_KEY, auth.token);

    const user: User = {
      id: auth.userId,
      email: auth.email,
      fullName: auth.fullName,
      role: auth.role,
      tenantId: auth.tenantId,
      organizationName: auth.organizationName
    };

    const tenant: Tenant = {
      id: auth.tenantId,
      name: auth.organizationName || 'Nexus Enterprise',
      slug: (auth.organizationName || 'nexus').toLowerCase().replace(/\s+/g, '-'),
      status: 'ACTIVE'
    };

    localStorage.setItem(USER_KEY, JSON.stringify(user));
    localStorage.setItem(TENANT_KEY, JSON.stringify(tenant));

    this.currentUser.set(user);
    this.currentTenant.set(tenant);
  }

  /**
   * Cleans localStorage and resets Signals.
   */
  private clearSession(): void {
    if (typeof window !== 'undefined') {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
      localStorage.removeItem(TENANT_KEY);
    }
    this.currentUser.set(null);
    this.currentTenant.set(null);
  }

  /**
   * Restores cached session from localStorage on startup.
   */
  private restoreSession(): void {
    if (typeof window === 'undefined') return;

    const token = localStorage.getItem(TOKEN_KEY);
    const userStr = localStorage.getItem(USER_KEY);
    const tenantStr = localStorage.getItem(TENANT_KEY);

    if (token && userStr) {
      try {
        const user = JSON.parse(userStr) as User;
        const tenant = tenantStr ? JSON.parse(tenantStr) as Tenant : {
          id: user.tenantId,
          name: user.organizationName || 'Enterprise Workspace',
          slug: 'workspace',
          status: 'ACTIVE'
        };

        this.currentUser.set(user);
        this.currentTenant.set(tenant);

        // Verify asynchronously in background with /api/v1/auth/me
        this.loadCurrentUser().subscribe();
      } catch {
        this.clearSession();
      }
    }
  }
}

