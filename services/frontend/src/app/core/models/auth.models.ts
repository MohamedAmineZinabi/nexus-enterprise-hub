export interface Tenant {
  id: string;
  name: string;
  slug: string;
  status?: string;
}

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: string;
  tenantId: string;
  organizationName?: string;
}

export interface AuthResponse {
  token: string;
  tokenType: string;
  userId: string;
  tenantId: string;
  email: string;
  fullName: string;
  role: string;
  organizationName: string;
  expiresInMs: number;
}

export interface UserProfileResponse {
  id: string;
  email: string;
  fullName: string;
  role: string;
  enabled: boolean;
  tenant: {
    id: string;
    name: string;
    slug: string;
    status: string;
  };
  createdAt?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  organizationName: string;
  organizationSlug: string;
  fullName: string;
  email: string;
  password: string;
}

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
  errors?: any;
  timestamp?: string;
}

