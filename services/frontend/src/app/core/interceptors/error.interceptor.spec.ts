import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpContext, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SILENT_HTTP_ERRORS, errorInterceptor } from './error.interceptor';
import { AuthService } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;
  let notifications: NotificationService;

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['logout']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authService }
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    notifications = TestBed.inject(NotificationService);
    notifications.clear();
  });

  afterEach(() => httpMock.verify());

  function requestWithStatus(
    status: number,
    url = '/api/v1/tasks',
    options: { headers?: Record<string, string>; context?: HttpContext } = {}
  ): HttpErrorResponse | undefined {
    let received: HttpErrorResponse | undefined;
    http.get(url, { context: options.context }).subscribe({
      next: () => fail('expected the request to error'),
      error: (err: HttpErrorResponse) => (received = err)
    });
    const req = httpMock.expectOne(url);
    if (status === 0) {
      req.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    } else {
      req.flush({ success: false, message: 'err' }, { status, statusText: 'ERR', headers: options.headers });
    }
    return received;
  }

  it('logs out on 401 and rethrows the error', () => {
    const err = requestWithStatus(401);

    expect(authService.logout).toHaveBeenCalledTimes(1);
    expect(err?.status).toBe(401);
  });

  it('does not log out on 401 from the login endpoint (invalid credentials)', () => {
    requestWithStatus(401, '/api/v1/auth/login');

    expect(authService.logout).not.toHaveBeenCalled();
  });

  for (const status of [403, 429, 500, 503, 0]) {
    it(`does not log out on ${status}, surfaces a notification and rethrows`, () => {
      const err = requestWithStatus(status);

      expect(authService.logout).not.toHaveBeenCalled();
      expect(err?.status).toBe(status);
      expect(notifications.notifications().length).toBe(1);
    });
  }

  it('includes Retry-After seconds in the 429 notification', () => {
    requestWithStatus(429, '/api/v1/tasks', { headers: { 'Retry-After': '12' } });

    expect(notifications.notifications()[0].message).toContain('12s');
  });

  it('suppresses notifications for requests flagged SILENT_HTTP_ERRORS', () => {
    const context = new HttpContext().set(SILENT_HTTP_ERRORS, true);
    requestWithStatus(503, '/api/v1/auth/me', { context });

    expect(notifications.notifications().length).toBe(0);
    expect(authService.logout).not.toHaveBeenCalled();
  });
});
