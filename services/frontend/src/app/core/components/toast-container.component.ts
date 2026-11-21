import { Component, inject } from '@angular/core';
import { NotificationService, NotificationType } from '../services/notification.service';

/**
 * Global toast outlet for user-facing notifications (HTTP errors, rate limits, outages).
 */
@Component({
  selector: 'app-toast-container',
  standalone: true,
  template: `
    <div
      class="fixed bottom-6 right-6 z-[60] space-y-2 max-w-sm w-[calc(100%-3rem)] pointer-events-none"
      role="region"
      aria-live="polite"
      aria-label="Notifications"
    >
      @for (n of notificationService.notifications(); track n.id) {
        <div
          role="alert"
          [class]="toastClass(n.type)"
        >
          <div class="flex-1 min-w-0">
            <h4 class="text-xs font-bold tracking-wide uppercase">{{ n.title }}</h4>
            <p class="text-xs mt-0.5 opacity-90 break-words">{{ n.message }}</p>
          </div>
          <button
            type="button"
            (click)="notificationService.dismiss(n.id)"
            class="text-slate-400 hover:text-white shrink-0"
            aria-label="Dismiss notification"
          >
            <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      }
    </div>
  `
})
export class ToastContainerComponent {
  readonly notificationService = inject(NotificationService);

  toastClass(type: NotificationType): string {
    const base = 'pointer-events-auto flex items-start gap-3 p-4 rounded-xl border shadow-xl backdrop-blur-md ';
    switch (type) {
      case 'error':
        return base + 'bg-red-950/90 border-red-800/80 text-red-200';
      case 'warning':
        return base + 'bg-amber-950/90 border-amber-800/80 text-amber-200';
      case 'success':
        return base + 'bg-emerald-950/90 border-emerald-800/80 text-emerald-200';
      default:
        return base + 'bg-slate-900/90 border-slate-700/80 text-slate-200';
    }
  }
}
