import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AgentStudioService } from './agent-studio.service';
import { AuthService } from './auth.service';

type Listener = (e: MessageEvent) => void;

/** Minimal controllable stand-in for the browser EventSource. */
class MockEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static instances: MockEventSource[] = [];

  readonly CONNECTING = 0;
  readonly OPEN = 1;
  readonly CLOSED = 2;

  readyState = MockEventSource.CONNECTING;
  onmessage: Listener | null = null;
  onerror: ((e: Event) => void) | null = null;
  closed = false;
  private readonly listeners = new Map<string, Listener[]>();

  constructor(public readonly url: string) {
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: Listener): void {
    this.listeners.set(type, [...(this.listeners.get(type) || []), listener]);
  }

  close(): void {
    this.closed = true;
    this.readyState = MockEventSource.CLOSED;
  }

  emit(type: string, payload: unknown): void {
    this.readyState = MockEventSource.OPEN;
    const event = { data: JSON.stringify(payload) } as MessageEvent;
    (this.listeners.get(type) || []).forEach(l => l(event));
  }

  fail(readyState: number): void {
    this.readyState = readyState;
    this.onerror?.(new Event('error'));
  }
}

describe('AgentStudioService (SSE handling)', () => {
  let service: AgentStudioService;
  let httpMock: HttpTestingController;
  let originalEventSource: typeof EventSource;

  const latest = (): MockEventSource => MockEventSource.instances[MockEventSource.instances.length - 1];

  beforeEach(() => {
    originalEventSource = window.EventSource;
    MockEventSource.instances = [];
    (window as unknown as { EventSource: unknown }).EventSource = MockEventSource;

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { getToken: () => 'jwt.token+/=' } }
      ]
    });
    service = TestBed.inject(AgentStudioService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.closeStream();
    (window as unknown as { EventSource: unknown }).EventSource = originalEventSource;
    httpMock.verify();
  });

  it('opens the stream URL with the URL-encoded JWT as a query parameter', () => {
    service.connectSse('task-1');

    expect(latest().url).toBe(`/api/v1/tasks/task-1/stream?token=${encodeURIComponent('jwt.token+/=')}`);
  });

  it('updates streaming state and node pipeline from HANDSHAKE and TASK_PROGRESS events', () => {
    service.connectSse('task-1');
    const es = latest();

    es.emit('HANDSHAKE', { timestamp: '2026-01-01T00:00:00Z' });
    expect(service.streamStatus()).toBe('STREAMING');
    expect(service.activeNode()).toBe('PLANNER');

    es.emit('TASK_PROGRESS', {
      taskId: 'task-1',
      nodeName: 'researcher',
      status: 'PROCESSING',
      durationMs: 120,
      message: 'Running hybrid search',
      timestamp: '2026-01-01T00:00:01Z'
    });

    expect(service.timelineEvents().length).toBe(2);
    expect(service.timelineEvents()[1].message).toBe('Running hybrid search');
    expect(service.activeNode()).toBe('RESEARCHER');
    const nodes = service.nodeStates();
    expect(nodes.find(n => n.nodeName === 'PLANNER')?.status).toBe('completed');
    expect(nodes.find(n => n.nodeName === 'RESEARCHER')?.status).toBe('running');
    expect(nodes.find(n => n.nodeName === 'RESEARCHER')?.durationMs).toBe(120);
    expect(nodes.find(n => n.nodeName === 'CRITIC')?.status).toBe('idle');
  });

  it('applies the final report and closes the stream on TASK_COMPLETED', () => {
    service.connectSse('task-1');
    const es = latest();

    es.emit('TASK_COMPLETED', {
      taskId: 'task-1',
      status: 'COMPLETED',
      report: '# Executive Report',
      citations: [{ title: 'Policy.pdf', score: 0.91 }],
      totalDurationMs: 4200
    });

    expect(service.streamStatus()).toBe('COMPLETED');
    expect(service.finalReport()).toBe('# Executive Report');
    expect(service.citations().length).toBe(1);
    expect(service.totalDurationMs()).toBe(4200);
    expect(service.nodeStates().every(n => n.status === 'completed')).toBeTrue();
    expect(es.closed).toBeTrue();

    // Completion refreshes the history list
    const req = httpMock.expectOne(r => r.url.startsWith('/api/v1/tasks?'));
    req.flush({ success: true, message: 'ok', data: { items: [], currentPage: 0, totalPages: 0, totalItems: 7, pageSize: 10 } });
    expect(service.totalTasks()).toBe(7);
  });

  it('marks the stream FAILED when TASK_COMPLETED reports a failure', () => {
    service.connectSse('task-1');
    latest().emit('TASK_COMPLETED', { status: 'FAILED', report: '', errorDetails: 'LLM timeout' });

    expect(service.streamStatus()).toBe('FAILED');
    expect(latest().closed).toBeTrue();
    httpMock.expectOne(r => r.url.startsWith('/api/v1/tasks?')).flush({ success: true, message: 'ok', data: { items: [] } });
  });

  it('recovers the result via REST when the stream errors after the task completed', () => {
    service.connectSse('task-1');
    const es = latest();

    es.fail(MockEventSource.CLOSED);

    const req = httpMock.expectOne('/api/v1/tasks/task-1');
    req.flush({
      success: true,
      message: 'ok',
      data: { taskId: 'task-1', status: 'COMPLETED', resultSummary: 'Recovered summary', taskType: 'DEEP_RESEARCH', prompt: 'p', createdAt: '' }
    });

    expect(service.streamStatus()).toBe('COMPLETED');
    expect(service.finalReport()).toBe('Recovered summary');
    expect(es.closed).toBeTrue();
  });

  it('keeps the auto-reconnecting stream open while the task is still PROCESSING', () => {
    service.connectSse('task-1');
    const es = latest();

    es.fail(MockEventSource.CONNECTING);
    httpMock.expectOne('/api/v1/tasks/task-1').flush({
      success: true,
      message: 'ok',
      data: { taskId: 'task-1', status: 'PROCESSING', taskType: 'DEEP_RESEARCH', prompt: 'p', createdAt: '' }
    });

    expect(service.streamStatus()).toBe('CONNECTING');
    expect(es.closed).toBeFalse();
    expect(service.error()).toBeNull();
  });

  it('reports an ERROR and closes the stream when the status check also fails', () => {
    service.connectSse('task-1');
    const es = latest();

    es.fail(MockEventSource.CLOSED);
    httpMock.expectOne('/api/v1/tasks/task-1').flush(null, { status: 503, statusText: 'Service Unavailable' });

    expect(service.streamStatus()).toBe('ERROR');
    expect(service.error()).toContain('Lost connection');
    expect(es.closed).toBeTrue();
  });
});
