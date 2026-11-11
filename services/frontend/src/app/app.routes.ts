import { Routes } from '@angular/router';
import { authGuard, publicOnlyGuard } from './core/guards/auth.guard';
import { ShellComponent } from './core/layout/shell.component';

export const routes: Routes = [
  // Public Authentication Routes
  {
    path: 'auth/login',
    canActivate: [publicOnlyGuard],
    loadComponent: () => import('./features/auth/login.component').then(m => m.LoginComponent)
  },
  {
    path: 'auth/register',
    canActivate: [publicOnlyGuard],
    loadComponent: () => import('./features/auth/register.component').then(m => m.RegisterComponent)
  },

  // Protected Application Shell
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      {
        path: 'knowledge-base',
        loadComponent: () => import('./features/knowledge-base/knowledge-base.component').then(m => m.KnowledgeBaseComponent)
      },
      {
        path: 'agent-studio',
        loadComponent: () => import('./features/agent-studio/agent-studio.component').then(m => m.AgentStudioComponent)
      },
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full'
      }
    ]
  },

  // Fallback Route
  {
    path: '**',
    redirectTo: 'dashboard'
  }
];
