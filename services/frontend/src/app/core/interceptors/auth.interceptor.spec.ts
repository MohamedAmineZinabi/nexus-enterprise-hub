import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let token: string | null;

  beforeEach(() => {
    token = null;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { getToken: () => token } }
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('adds a Bearer Authorization header to /api requests when a token exists', () => {
    token = 'header.payload.signature';

    http.get('/api/v1/documents').subscribe();

    const req = httpMock.expectOne('/api/v1/documents');
    expect(req.request.headers.get('Authorization')).toBe('Bearer header.payload.signature');
    req.flush({});
  });

  it('does not add an Authorization header when there is no token', () => {
    token = null;

    http.get('/api/v1/documents').subscribe();

    const req = httpMock.expectOne('/api/v1/documents');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('does not leak the token to non-API URLs', () => {
    token = 'header.payload.signature';

    http.get('/nginx-health', { responseType: 'text' }).subscribe();

    const req = httpMock.expectOne('/nginx-health');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush('OK');
  });
});
