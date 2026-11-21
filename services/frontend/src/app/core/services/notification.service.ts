import { Injectable, signal } from '@angular/core';

export type NotificationType = 'success' | 'error' | 'warning' | 'info';

export interface AppNotification {
  id: number;
  type: NotificationType;
  title: string;
  message: string;
}

const DEFAULT_TIMEOUT_MS = 6000;
const DEDUPE_WINDOW_MS = 4000;
const MAX_VISIBLE = 4;

/**
 * Lightweight, signal-based global toast notification store.
 * Rendered by `ToastContainerComponent` mounted once in the root component.
 */
@Injectable({
  providedIn: 'root'
})
export class NotificationService {
  readonly notifications = signal<AppNotification[]>([]);

  private nextId = 1;
  private readonly recent = new Map<string, number>();

  show(type: NotificationType, title: string, message: string, timeoutMs: number = DEFAULT_TIMEOUT_MS): void {
    // Suppress identical toasts fired in quick succession (e.g. parallel failing requests)
    const key = `${type}|${title}|${message}`;
    const now = Date.now();
    const last = this.recent.get(key);
    if (last !== undefined && now - last < DEDUPE_WINDOW_MS) {
      return;
    }
    this.recent.set(key, now);

    const id = this.nextId++;
    this.notifications.update(list => [...list, { id, type, title, message }].slice(-MAX_VISIBLE));

    if (timeoutMs > 0) {
      setTimeout(() => this.dismiss(id), timeoutMs);
    }
  }

  error(title: string, message: string): void {
    this.show('error', title, message);
  }

  warning(title: string, message: string): void {
    this.show('warning', title, message);
  }

  dismiss(id: number): void {
    this.notifications.update(list => list.filter(n => n.id !== id));
  }

  clear(): void {
    this.notifications.set([]);
    this.recent.clear();
  }
}
