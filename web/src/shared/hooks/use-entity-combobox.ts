import { useEffect, useId, useRef, useState, type FocusEvent, type KeyboardEvent } from "react";
import { useDebouncedSearch } from "@/shared/lib/use-debounced-search";

export function useEntityCombobox({
  onChange,
  getOptionIds,
  allowClear,
}: {
  onChange: (id: string) => void;
  getOptionIds: () => string[];
  allowClear: boolean;
}) {
  const [text, setText] = useState("");
  const debounced = useDebouncedSearch(text);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const boxRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const inputId = useId();
  const listId = useId();

  useEffect(() => {
    function closeOnOutsideClick(event: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) {
        setOpen(false);
        setEditing(false);
        setActiveIndex(-1);
      }
    }
    document.addEventListener("mousedown", closeOnOutsideClick);
    return () => document.removeEventListener("mousedown", closeOnOutsideClick);
  }, []);

  useEffect(() => {
    if (activeIndex < 0) return;
    listRef.current?.querySelector<HTMLElement>(`[data-option-index="${activeIndex}"]`)
      ?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  function close() {
    setOpen(false);
    setEditing(false);
    setActiveIndex(-1);
  }

  function startSelecting() {
    setText("");
    setEditing(true);
    setOpen(true);
    setActiveIndex(-1);
    requestAnimationFrame(() => inputRef.current?.focus());
  }

  function select(id: string) {
    onChange(id);
    setText("");
    close();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    const optionIds = getOptionIds();
    const ids = allowClear ? ["", ...optionIds] : optionIds;
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
      if (ids.length === 0) return;
      setActiveIndex((current) => event.key === "ArrowDown"
        ? current >= ids.length - 1 ? 0 : current + 1
        : current <= 0 ? ids.length - 1 : current - 1);
    } else if (event.key === "Enter" && open && activeIndex >= 0) {
      event.preventDefault();
      select(ids[activeIndex] ?? "");
    }
  }

  const comboboxProps = {
    role: "combobox",
    "aria-expanded": open,
    "aria-controls": listId,
    "aria-autocomplete": "list" as const,
    "aria-activedescendant": open && activeIndex >= 0 ? `${listId}-option-${activeIndex}` : undefined,
    onKeyDown: handleKeyDown,
    onBlur: (event: FocusEvent<HTMLInputElement>) => {
      if (!boxRef.current?.contains(event.relatedTarget as Node)) close();
    },
  };

  function optionProps(index: number, selected: boolean) {
    return {
      id: `${listId}-option-${index}`,
      role: "option",
      "aria-selected": selected,
      "data-option-index": index,
      onMouseEnter: () => setActiveIndex(index),
    } as const;
  }

  return {
    text, setText, debounced, open, setOpen, editing, activeIndex, setActiveIndex,
    boxRef, inputRef, listRef, inputId, listId, startSelecting, select, close,
    comboboxProps, optionProps,
  };
}
