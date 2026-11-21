import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, map, of, switchMap, timer } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { SILENT_HTTP_ERRORS } from '../interceptors/error.interceptor';

const HEALTH_POLL_INTERVAL_MS = 30000;

type SystemStatus = 'Checking' | 'Online' | 'Degraded' | 'Offline';

interface HealthProbe {
  gateway: boolean;
  api: boolean;
  checked: boolean;
}

interface NavItem {
  label: string;
  route: string;
  icon: string;
  badge?: string;
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="min-h-screen bg-slate-950 text-slate-100 flex flex-col antialiased">
      <!-- Top Navigation Bar -->
      <header class="h-16 border-b border-slate-800 bg-slate-900/80 backdrop-blur-md sticky top-0 z-30 flex items-center justify-between px-4 sm:px-6">
        
        <!-- Left: Brand & Mobile Sidebar Toggle -->
        <div class="flex items-center gap-4">
          <button
            type="button"
            (click)="toggleSidebar()"
            class="lg:hidden p-2 rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 focus:outline-none"
            aria-label="Toggle navigation menu"
          >
            <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>

          <div class="flex items-center gap-3">
            <div class="w-9 h-9 rounded-lg bg-gradient-to-tr from-brand-600 to-indigo-500 flex items-center justify-center shadow-md shadow-brand-500/20 ring-1 ring-white/10">
              <svg class="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <div class="hidden sm:block">
              <span class="text-base font-bold tracking-tight text-white">NexusEnterprise</span>
            </div>
          </div>
        </div>

        <!-- Center: Tenant Context Badge -->
        <div class="flex items-center gap-2">
          @if (tenant(); as t) {
            <div class="flex items-center gap-2 bg-slate-800/80 border border-slate-700/60 rounded-full px-3 py-1 shadow-sm">
              <span class="relative flex h-2 w-2">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span class="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
              </span>
              <span class="text-xs font-medium text-slate-300">Tenant:</span>
              <span class="text-xs font-semibold text-white tracking-wide">{{ t.name }}</span>
              <span class="text-[10px] font-mono text-slate-400 uppercase bg-slate-900/90 px-1.5 py-0.5 rounded border border-slate-700/50">
                {{ t.status || 'ACTIVE' }}
              </span>
            </div>
          }
        </div>

        <!-- Right: User Initials, Role, and Logout -->
        <div class="flex items-center gap-3">
          @if (user(); as u) {
            <div class="flex items-center gap-3 pl-3 border-l border-slate-800">
              <!-- Avatar Initials -->
              <div class="w-9 h-9 rounded-full bg-gradient-to-tr from-slate-800 to-slate-700 border border-slate-600 flex items-center justify-center text-xs font-bold text-white shadow-inner">
                {{ userInitials() }}
              </div>

              <!-- Name & Role (Desktop) -->
              <div class="hidden md:block text-left">
                <div class="text-xs font-semibold text-slate-200">{{ u.fullName }}</div>
                <div class="text-[10px] font-mono text-brand-400 uppercase tracking-wider">{{ u.role }}</div>
              </div>

              <!-- Logout Button -->
              <button
                type="button"
                (click)="onLogout()"
                title="Sign out"
                class="p-2 rounded-lg text-slate-400 hover:text-red-400 hover:bg-slate-800/80 transition-colors focus:outline-none ml-1"
              >
                <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                </svg>
              </button>
            </div>
          }
        </div>
      </header>

      <div class="flex-1 flex overflow-hidden">
        <!-- Sidebar Navigation -->
        <aside
          [class.translate-x-0]="sidebarOpen()"
          [class.-translate-x-full]="!sidebarOpen()"
          class="lg:translate-x-0 fixed lg:static inset-y-0 left-0 z-20 w-64 bg-slate-900/95 lg:bg-slate-900/60 border-r border-slate-800 flex flex-col justify-between transition-transform duration-200 ease-in-out backdrop-blur-md pt-16 lg:pt-0"
        >
          <!-- Navigation Links -->
          <nav class="p-4 space-y-1.5 overflow-y-auto">
            <div class="text-[10px] font-mono uppercase tracking-wider text-slate-400 px-3 py-1 font-semibold">
              Platform Modules
            </div>

            @for (item of navItems; track item.route) {
              <a
                [routerLink]="item.route"
                routerLinkActive="bg-brand-600/20 text-brand-300 border-brand-500 font-semibold"
                [routerLinkActiveOptions]="{ exact: false }"
                (click)="closeMobileSidebar()"
                class="flex items-center justify-between px-3.5 py-2.5 rounded-lg text-sm text-slate-400 hover:text-slate-100 hover:bg-slate-800/70 border-l-2 border-transparent transition-all group"
              >
                <div class="flex items-center gap-3">
                  <!-- SVG Icon -->
                  @switch (item.icon) {
                    @case ('dashboard') {
                      <svg class="w-5 h-5 group-hover:text-brand-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" />
                      </svg>
                    }
                    @case ('knowledge') {
                      <svg class="w-5 h-5 group-hover:text-brand-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
                      </svg>
                    }
                    @case ('agents') {
                      <svg class="w-5 h-5 group-hover:text-brand-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                      </svg>
                    }
                  }
                  <span>{{ item.label }}</span>
                </div>
                @if (item.badge) {
                  <span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-brand-900/60 text-brand-300 border border-brand-800/60">
                    {{ item.badge }}
                  </span>
                }
              </a>
            }
          </nav>

          <!-- System Status Footer -->
          <div class="p-4 border-t border-slate-800 bg-slate-950/40">
            <div class="flex items-center justify-between text-xs text-slate-400 mb-2">
              <span class="font-medium text-slate-300">System Status</span>
              <span
                class="text-[10px] font-mono flex items-center gap-1.5 uppercase"
                [class]="statusTextClass()"
                role="status"
                aria-live="polite"
              >
                <span class="w-1.5 h-1.5 rounded-full" [class]="statusDotClass()"></span>
                {{ systemStatus() }}
              </span>
            </div>
            <div class="space-y-1 text-[11px] font-mono text-slate-400">
              <div class="flex items-center justify-between">
                <span>Gateway</span>
                <span [class]="probe().gateway ? 'text-emerald-400' : 'text-red-400'">
                  {{ probe().checked ? (probe().gateway ? 'UP' : 'DOWN') : '...' }}
                </span>
              </div>
              <div class="flex items-center justify-between">
                <span>Core API</span>
                <span [class]="probe().api ? 'text-emerald-400' : 'text-red-400'">
                  {{ probe().checked ? (probe().api ? 'UP' : 'DOWN') : '...' }}
                </span>
              </div>
              @if (lastChecked(); as checkedAt) {
                <div class="text-[10px] text-slate-500 pt-1">Checked {{ checkedAt | date:'HH:mm:ss' }}</div>
              }
            </div>
          </div>
        </aside>

        <!-- Backdrop for mobile sidebar -->
        @if (sidebarOpen()) {
          <div
            (click)="closeMobileSidebar()"
            class="fixed inset-0 bg-black/60 backdrop-blur-sm z-10 lg:hidden"
          ></div>
        }

        <!-- Main Workspace Outlet -->
        <main class="flex-1 overflow-y-auto bg-slate-950 p-4 sm:p-6 lg:p-8">
          <div class="max-w-7xl mx-auto">
            <router-outlet></router-outlet>
          </div>
        </main>
      </div>
    </div>
  `
})
export class ShellComponent {
  private readonly authService = inject(AuthService);
  private readonly http = inject(HttpClient);

  readonly sidebarOpen = signal<boolean>(false);

  readonly user = this.authService.currentUser;
  readonly tenant = this.authService.currentTenant;

  readonly userInitials = computed(() => {
    const name = this.user()?.fullName || 'User';
    return name
      .split(' ')
      .map(part => part[0])
      .join('')
      .substring(0, 2)
      .toUpperCase();
  });

  readonly navItems: NavItem[] = [
    { label: 'Executive Dashboard', route: '/dashboard', icon: 'dashboard' },
    { label: 'Knowledge Base / RAG', route: '/knowledge-base', icon: 'knowledge', badge: 'RAG' },
    { label: 'Agent Studio', route: '/agent-studio', icon: 'agents' }
  ];

  // Live gateway / API health indicator
  readonly probe = signal<HealthProbe>({ gateway: false, api: false, checked: false });
  readonly lastChecked = signal<Date | null>(null);

  readonly systemStatus = computed<SystemStatus>(() => {
    const p = this.probe();
    if (!p.checked) return 'Checking';
    if (p.gateway && p.api) return 'Online';
    if (p.gateway || p.api) return 'Degraded';
    return 'Offline';
  });

  readonly statusTextClass = computed(() => {
    switch (this.systemStatus()) {
      case 'Online': return 'text-emerald-400';
      case 'Degraded': return 'text-amber-400';
      case 'Offline': return 'text-red-400';
      default: return 'text-slate-400';
    }
  });

  readonly statusDotClass = computed(() => {
    switch (this.systemStatus()) {
      case 'Online': return 'bg-emerald-500';
      case 'Degraded': return 'bg-amber-500 animate-pulse';
      case 'Offline': return 'bg-red-500 animate-pulse';
      default: return 'bg-slate-500';
    }
  });

  constructor() {
    const silent = new HttpContext().set(SILENT_HTTP_ERRORS, true);

    const gateway$ = this.http.get('/nginx-health', { responseType: 'text', context: silent }).pipe(
      map(() => true),
      catchError(() => of(false))
    );
    // Cheap authenticated call: proves core-service, JWT validation and the token
    // revocation store are all reachable. 429 still means the API is up.
    const api$ = this.http.get('/api/v1/auth/me', { context: silent }).pipe(
      map(() => true),
      catchError((err: unknown) => of(err instanceof HttpErrorResponse && err.status === 429))
    );

    timer(0, HEALTH_POLL_INTERVAL_MS).pipe(
      switchMap(() => forkJoin({ gateway: gateway$, api: api$ })),
      takeUntilDestroyed()
    ).subscribe(({ gateway, api }) => {
      this.probe.set({ gateway, api, checked: true });
      this.lastChecked.set(new Date());
    });
  }

  toggleSidebar(): void {
    this.sidebarOpen.update(v => !v);
  }

  closeMobileSidebar(): void {
    this.sidebarOpen.set(false);
  }

  onLogout(): void {
    this.authService.logout();
  }
}

