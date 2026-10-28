import os
import uuid
import logging
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Any
from langchain_text_splitters import RecursiveCharacterTextSplitter

logger = logging.getLogger(__name__)


@dataclass
class ParsedChunk:
    """Represents a discrete text chunk ready for vectorization and indexing."""
    chunk_id: str
    document_id: str
    content: str
    chunk_index: int
    page_number: Optional[int] = None
    citation: str = ""
    metadata: Dict[str, str] = field(default_factory=dict)


class DocumentParser:
    """Loads and chunks documents into semantic pieces using LangChain text splitters."""

    def __init__(self, chunk_size: int = 500, chunk_overlap: int = 50):
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        self.text_splitter = RecursiveCharacterTextSplitter(
            chunk_size=self.chunk_size,
            chunk_overlap=self.chunk_overlap,
            length_function=len,
            separators=["\n\n", "\n", ". ", "; ", ", ", " ", ""]
        )

    def parse_and_chunk(
        self,
        file_path: str,
        document_id: str,
        mime_type: Optional[str] = None,
        base_metadata: Optional[Dict[str, str]] = None
    ) -> List[ParsedChunk]:
        """Read a document from disk, extract its text, and partition it into chunks."""
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File not found on disk at: {file_path}")

        filename = os.path.basename(file_path)
        ext = os.path.splitext(filename)[1].lower()
        meta = base_metadata.copy() if base_metadata else {}
        meta["filename"] = filename
        meta["document_id"] = document_id

        logger.info(f"Parsing document '{filename}' (type: {mime_type or ext}) for document_id={document_id}")

        pages_or_sections = self._extract_text_sections(file_path, ext, mime_type)
        chunks: List[ParsedChunk] = []
        global_chunk_idx = 0

        for page_num, section_text in pages_or_sections:
            if not section_text or not section_text.strip():
                continue

            raw_chunks = self.text_splitter.split_text(section_text)
            for chunk_text in raw_chunks:
                clean_text = chunk_text.strip()
                if not clean_text:
                    continue

                chunk_id = f"{document_id}_chk_{global_chunk_idx:04d}"
                citation = f"{filename}" + (f" (p. {page_num})" if page_num else f" [chunk {global_chunk_idx + 1}]")

                chunk_meta = meta.copy()
                chunk_meta["chunk_index"] = str(global_chunk_idx)
                if page_num is not None:
                    chunk_meta["page_number"] = str(page_num)

                chunks.append(
                    ParsedChunk(
                        chunk_id=chunk_id,
                        document_id=document_id,
                        content=clean_text,
                        chunk_index=global_chunk_idx,
                        page_number=page_num,
                        citation=citation,
                        metadata=chunk_meta
                    )
                )
                global_chunk_idx += 1

        logger.info(f"Extracted {len(chunks)} chunks from '{filename}'.")
        return chunks

    def _extract_text_sections(
        self,
        file_path: str,
        ext: str,
        mime_type: Optional[str]
    ) -> List[tuple[Optional[int], str]]:
        """Extract sections or pages with 1-based page numbers."""
        is_pdf = ext == ".pdf" or (mime_type and "pdf" in mime_type.lower())

        if is_pdf:
            return self._extract_pdf(file_path)
        else:
            return self._extract_plaintext(file_path)

    def _extract_pdf(self, file_path: str) -> List[tuple[Optional[int], str]]:
        """Extract text per page from a PDF file using pypdf."""
        sections = []
        try:
            import pypdf
            reader = pypdf.PdfReader(file_path)
            for page_idx, page in enumerate(reader.pages):
                page_text = page.extract_text() or ""
                sections.append((page_idx + 1, page_text))
        except Exception as e:
            logger.warning(f"pypdf extraction failed for {file_path}: {e}. Falling back to raw text read.")
            return self._extract_plaintext(file_path)
        return sections

    def _extract_plaintext(self, file_path: str) -> List[tuple[Optional[int], str]]:
        """Extract text from plain text, Markdown, or other textual formats."""
        try:
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
            return [(None, content)]
        except Exception as e:
            logger.error(f"Failed to read file {file_path}: {e}")
            raise
