import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  template: `
    <div class="min-h-screen bg-slate-950 flex flex-col justify-center py-12 sm:px-6 lg:px-8 relative overflow-hidden">
      <!-- Ambient Background Glow -->
      <div class="absolute -top-40 -left-40 w-96 h-96 bg-brand-600/20 rounded-full blur-3xl pointer-events-none"></div>
      <div class="absolute -bottom-40 -right-40 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none"></div>

      <div class="sm:mx-auto sm:w-full sm:max-w-md z-10">
        <!-- Brand Header -->
        <div class="flex items-center justify-center gap-3 mb-4">
          <div class="w-12 h-12 rounded-xl bg-gradient-to-tr from-brand-600 to-indigo-400 flex items-center justify-center shadow-lg shadow-brand-500/25 ring-1 ring-white/20">
            <svg class="w-7 h-7 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          </div>
          <div>
            <h1 class="text-2xl font-bold tracking-tight text-white">NexusEnterprise</h1>
            <p class="text-xs font-medium text-brand-400 uppercase tracking-widest">Multi-Tenant Setup</p>
          </div>
        </div>
        <h2 class="text-center text-xl font-semibold text-slate-200">Register new enterprise workspace</h2>
        <p class="mt-1 text-center text-sm text-slate-400">
          Already registered?
          <a routerLink="/auth/login" class="font-medium text-brand-400 hover:text-brand-300 transition-colors">
            Sign in to existing account
          </a>
        </p>
      </div>

      <div class="mt-8 sm:mx-auto sm:w-full sm:max-w-lg z-10 px-4 sm:px-0">
        <div class="bg-slate-900/90 backdrop-blur-xl border border-slate-800 py-8 px-6 shadow-2xl rounded-2xl sm:px-10">

          <!-- Error Alert Banner -->
          @if (errorMessage()) {
            <div class="mb-5 rounded-lg bg-red-950/50 border border-red-800/60 p-4 text-sm text-red-300 flex items-start gap-3 animate-fadeIn">
              <svg class="w-5 h-5 text-red-400 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              <div>
                <p class="font-medium">Registration Failed</p>
                <p class="text-xs text-red-400 mt-0.5">{{ errorMessage() }}</p>
              </div>
            </div>
          }

          <form [formGroup]="registerForm" (ngSubmit)="onSubmit()" class="space-y-4">
            <!-- Organization Name & Slug in 2 columns -->
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label for="orgName" class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                  Organization Name
                </label>
                <input
                  id="orgName"
                  type="text"
                  formControlName="organizationName"
                  (input)="onOrgNameChange()"
                  placeholder="Acme Aerospace"
                  class="mt-1.5 block w-full rounded-lg bg-slate-950/70 border border-slate-700/80 px-3.5 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 transition-all"
                  [class.border-red-500]="isFieldInvalid('organizationName')"
                />
                @if (isFieldInvalid('organizationName')) {
                  <p class="mt-1 text-xs text-red-400">Organization name required (2-100 chars).</p>
                }
              </div>

              <div>
                <label for="orgSlug" class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                  Workspace Slug
                </label>
                <input
                  id="orgSlug"
                  type="text"
                  formControlName="organizationSlug"
                  placeholder="acme-aerospace"
                  class="mt-1.5 block w-full rounded-lg bg-slate-950/70 border border-slate-700/80 px-3.5 py-2.5 text-sm font-mono text-brand-300 placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 transition-all"
                  [class.border-red-500]="isFieldInvalid('organizationSlug')"
                />
                @if (isFieldInvalid('organizationSlug')) {
                  <p class="mt-1 text-xs text-red-400">Valid slug required.</p>
                }
              </div>
            </div>

            <!-- Full Name -->
            <div>
              <label for="fullName" class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                Administrator Full Name
              </label>
              <input
                id="fullName"
                type="text"
                formControlName="fullName"
                placeholder="Jane Doe"
                class="mt-1.5 block w-full rounded-lg bg-slate-950/70 border border-slate-700/80 px-3.5 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 transition-all"
                [class.border-red-500]="isFieldInvalid('fullName')"
              />
              @if (isFieldInvalid('fullName')) {
                <p class="mt-1 text-xs text-red-400">Full name is required.</p>
              }
            </div>

            <!-- Email -->
            <div>
              <label for="regEmail" class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                Admin Work Email
              </label>
              <input
                id="regEmail"
                type="email"
                formControlName="email"
                placeholder="admin@company.com"
                class="mt-1.5 block w-full rounded-lg bg-slate-950/70 border border-slate-700/80 px-3.5 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 transition-all"
                [class.border-red-500]="isFieldInvalid('email')"
              />
              @if (isFieldInvalid('email')) {
                <p class="mt-1 text-xs text-red-400">Please provide a valid work email.</p>
              }
            </div>

            <!-- Password -->
            <div>
              <label for="regPassword" class="block text-xs font-semibold uppercase tracking-wider text-slate-300">
                Master Password (min 8 characters)
              </label>
              <input
                id="regPassword"
                type="password"
                formControlName="password"
                placeholder="••••••••••••"
                class="mt-1.5 block w-full rounded-lg bg-slate-950/70 border border-slate-700/80 px-3.5 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-brand-500 transition-all"
                [class.border-red-500]="isFieldInvalid('password')"
              />
              @if (isFieldInvalid('password')) {
                <p class="mt-1 text-xs text-red-400">Password must be at least 8 characters long.</p>
              }
            </div>

            <!-- Submit Button -->
            <div class="pt-2">
              <button
                type="submit"
                [disabled]="registerForm.invalid || authService.isLoading()"
                class="w-full flex justify-center items-center py-2.5 px-4 rounded-lg text-sm font-semibold text-white bg-gradient-to-r from-brand-600 to-indigo-600 hover:from-brand-500 hover:to-indigo-500 shadow-md shadow-brand-600/30 disabled:opacity-50 disabled:cursor-not-allowed transition-all focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-offset-slate-900 focus:ring-brand-500"
              >
                @if (authService.isLoading()) {
                  <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                  </svg>
                  Creating Workspace...
                } @else {
                  Create Enterprise Workspace
                }
              </button>
            </div>
          </form>

        </div>
      </div>
    </div>
  `
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly errorMessage = signal<string | null>(null);

  readonly registerForm: FormGroup = this.fb.group({
    organizationName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    organizationSlug: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]]
  });

  isFieldInvalid(fieldName: string): boolean {
    const field = this.registerForm.get(fieldName);
    return !!(field && field.invalid && (field.dirty || field.touched));
  }

  onOrgNameChange(): void {
    const slugCtrl = this.registerForm.get('organizationSlug');
    if (!slugCtrl?.dirty) {
      const orgName = this.registerForm.get('organizationName')?.value || '';
      const slug = orgName
        .toLowerCase()
        .trim()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
      slugCtrl?.setValue(slug);
    }
  }

  onSubmit(): void {
    if (this.registerForm.invalid) {
      this.registerForm.markAllAsTouched();
      return;
    }

    this.errorMessage.set(null);
    this.authService.register(this.registerForm.value).subscribe({
      next: () => {
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        const msg = err.error?.message || err.error?.errors || 'Registration failed. Please check inputs.';
        this.errorMessage.set(typeof msg === 'string' ? msg : JSON.stringify(msg));
      }
    });
  }
}

